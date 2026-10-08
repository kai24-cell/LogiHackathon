package jp.cheapreview.bridge.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import jp.cheapreview.api.AppProtocol;
import jp.cheapreview.bridge.dto.BridgeDtos.ConnectionStatus;
import jp.cheapreview.bridge.dto.BridgeDtos.FolderResult;
import jp.cheapreview.bridge.dto.BridgeDtos.Registration;
import jp.cheapreview.bridge.dto.BridgeDtos.RequestKind;
import jp.cheapreview.bridge.dto.BridgeDtos.RequestStatus;
import jp.cheapreview.bridge.dto.BridgeDtos.RequestView;
import jp.cheapreview.bridge.dto.BridgeDtos.ResultInput;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BridgeService {
  private static final int MAX_WINDOW_NAME_LENGTH = 200;
  private final WorkspaceService workspaces;
  private final Duration heartbeatTimeout;
  private final Duration requestTimeout;
  private final int maxRequests;
  private final Clock clock;
  private final Map<String, FolderRequest> requests = new LinkedHashMap<>();
  private String bridgeId;
  private String windowId = "";
  private Instant lastHeartbeat = Instant.EPOCH;

  @Autowired
  public BridgeService(
      WorkspaceService workspaces,
      @Value("${cheapreview.bridge.heartbeat-timeout:30s}") Duration heartbeatTimeout,
      @Value("${cheapreview.bridge.request-timeout:120s}") Duration requestTimeout,
      @Value("${cheapreview.bridge.max-requests:100}") int maxRequests) {
    this(workspaces, heartbeatTimeout, requestTimeout, maxRequests, Clock.systemUTC());
  }

  // Clock injection makes expiry behavior testable without sleeping.
  BridgeService(
      WorkspaceService workspaces,
      Duration heartbeatTimeout,
      Duration requestTimeout,
      int maxRequests,
      Clock clock) {
    if (heartbeatTimeout.isNegative()
        || heartbeatTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || maxRequests < 1) {
      throw new IllegalArgumentException("Invalid bridge limits");
    }
    this.workspaces = workspaces;
    this.heartbeatTimeout = heartbeatTimeout;
    this.requestTimeout = requestTimeout;
    this.maxRequests = maxRequests;
    this.clock = clock;
  }

  public synchronized ConnectionStatus status() {
    return new ConnectionStatus(isConnected(), windowId, false, false);
  }

  public synchronized String register(Registration registration) {
    if (!AppProtocol.APP_VERSION.equals(registration.extensionVersion())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VERSION_MISMATCH");
    }
    String name = registration.windowId();
    if (name == null || name.isBlank() || name.length() > MAX_WINDOW_NAME_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW");
    }
    if (isConnected()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "BRIDGE_ALREADY_CONNECTED");
    }
    requests.clear();
    bridgeId = UUID.randomUUID().toString();
    windowId = name;
    lastHeartbeat = clock.instant();
    return bridgeId;
  }

  public synchronized void heartbeat(String candidateBridgeId) {
    requireBridge(candidateBridgeId);
    lastHeartbeat = clock.instant();
  }

  public synchronized String requestFolder(RequestKind kind) {
    if (kind == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }
    if (!isConnected()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "BRIDGE_DISCONNECTED");
    }
    expireRequests();
    if (requests.values().stream().anyMatch(request -> request.status.isActive())) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "BUSY");
    }
    if (requests.size() >= maxRequests) {
      requests.remove(requests.keySet().iterator().next());
    }
    FolderRequest request = new FolderRequest(clock.instant().plus(requestTimeout));
    requests.put(request.id, request);
    return request.id;
  }

  public synchronized Optional<RequestView> claimNext(String candidateBridgeId) {
    requireBridge(candidateBridgeId);
    expireRequests();
    for (FolderRequest request : requests.values()) {
      if (request.status == RequestStatus.PENDING) {
        request.status = RequestStatus.CLAIMED;
        return Optional.of(request.view());
      }
    }
    return Optional.empty();
  }

  public synchronized RequestView getRequest(String requestId) {
    expireRequests();
    return findRequest(requestId).view();
  }

  public synchronized RequestView complete(String requestId, ResultInput input) {
    requireBridge(input.bridgeId());
    expireRequests();
    FolderRequest request = findRequest(requestId);
    if (request.status != RequestStatus.CLAIMED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_EXPIRED");
    }
    if (input.status() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
    }
    switch (input.status()) {
      case COMPLETED -> {
        if (input.payload() == null || input.payload().uri() == null) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE");
        }
        request.result = workspaces.register(input.payload().uri());
        request.status = RequestStatus.COMPLETED;
      }
      case CANCELLED -> request.status = RequestStatus.CANCELLED;
      case FAILED -> request.status = RequestStatus.FAILED;
    }
    return request.view();
  }

  private boolean isConnected() {
    return bridgeId != null && lastHeartbeat.plus(heartbeatTimeout).isAfter(clock.instant());
  }

  private void requireBridge(String candidateBridgeId) {
    if (!isConnected() || !Objects.equals(candidateBridgeId, bridgeId)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_BRIDGE");
    }
  }

  private void expireRequests() {
    Instant now = clock.instant();
    for (FolderRequest request : requests.values()) {
      if (request.status.isActive() && !request.expiresAt.isAfter(now)) {
        request.status = RequestStatus.CANCELLED;
      }
    }
  }

  private FolderRequest findRequest(String requestId) {
    FolderRequest request = requests.get(requestId);
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }
    return request;
  }

  private static final class FolderRequest {
    private final String id = UUID.randomUUID().toString();
    private final Instant expiresAt;
    private RequestStatus status = RequestStatus.PENDING;
    private FolderResult result;

    private FolderRequest(Instant expiresAt) {
      this.expiresAt = expiresAt;
    }

    private RequestView view() {
      return new RequestView(id, RequestKind.FOLDER_PICK, status, result, expiresAt);
    }
  }
}
