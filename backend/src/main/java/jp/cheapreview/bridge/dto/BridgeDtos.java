package jp.cheapreview.bridge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public final class BridgeDtos {
  private BridgeDtos() {}

  public enum RequestKind {
    FOLDER_PICK
  }

  public enum RequestStatus {
    @JsonProperty("pending")
    PENDING,
    @JsonProperty("claimed")
    CLAIMED,
    @JsonProperty("completed")
    COMPLETED,
    @JsonProperty("cancelled")
    CANCELLED,
    @JsonProperty("failed")
    FAILED;

    public boolean isActive() {
      return this == PENDING || this == CLAIMED;
    }
  }

  public enum ResultStatus {
    @JsonProperty("completed")
    COMPLETED,
    @JsonProperty("cancelled")
    CANCELLED,
    @JsonProperty("failed")
    FAILED
  }

  public record Registration(String extensionVersion, String windowId) {}

  public record RegistrationResult(String bridgeId) {}

  public record BridgeInput(String bridgeId) {}

  public record PickInput(RequestKind kind) {}

  public record PickResult(String requestId) {}

  public record FolderPayload(String uri) {}

  public record ResultInput(String bridgeId, ResultStatus status, FolderPayload payload) {}

  public record FolderResult(String workspaceId, String name) {}

  public record RequestView(
      String requestId,
      RequestKind kind,
      RequestStatus status,
      FolderResult result,
      Instant expiresAt) {}

  public record ConnectionStatus(
      boolean bridgeConnected, String windowId, boolean demoAvailable, boolean translationReady) {}

  public record Health(String status, String appVersion, String protocolVersion) {}
}
