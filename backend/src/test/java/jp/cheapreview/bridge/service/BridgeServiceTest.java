package jp.cheapreview.bridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import jp.cheapreview.bridge.dto.BridgeDtos.Registration;
import jp.cheapreview.bridge.dto.BridgeDtos.RequestKind;
import jp.cheapreview.bridge.dto.BridgeDtos.RequestStatus;
import jp.cheapreview.bridge.dto.BridgeDtos.ResultInput;
import jp.cheapreview.bridge.dto.BridgeDtos.ResultStatus;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BridgeServiceTest {
  private final WorkspaceService workspaces = mock(WorkspaceService.class);
  private final MutableClock clock = new MutableClock();
  private final BridgeService bridge =
      new BridgeService(workspaces, Duration.ofSeconds(30), Duration.ofSeconds(120), 100, clock);

  @Test
  void claimIsAtomicAcrossConcurrentPolls() throws Exception {
    String bridgeId = bridge.register(new Registration("0.1.0", "test"));
    bridge.requestFolder(RequestKind.FOLDER_PICK);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> bridge.claimNext(bridgeId));
      var second = executor.submit(() -> bridge.claimNext(bridgeId));
      long claims =
          java.util.stream.Stream.of(first.get(), second.get())
              .filter(java.util.Optional::isPresent)
              .count();
      assertEquals(1, claims);
    }
  }

  @Test
  void deadlineCancelsRequestAndRejectsLateResult() {
    String bridgeId = bridge.register(new Registration("0.1.0", "test"));
    String requestId = bridge.requestFolder(RequestKind.FOLDER_PICK);
    bridge.claimNext(bridgeId);
    clock.advance(Duration.ofSeconds(120));
    assertEquals(RequestStatus.CANCELLED, bridge.getRequest(requestId).status());
    assertFalse(bridge.status().bridgeConnected());
    assertThrows(
        ResponseStatusException.class,
        () -> bridge.complete(requestId, new ResultInput(bridgeId, ResultStatus.COMPLETED, null)));
    verifyNoInteractions(workspaces);
  }

  @Test
  void cancellationDoesNotRegisterWorkspaceAndSecondWindowCannotTakeOver() {
    String bridgeId = bridge.register(new Registration("0.1.0", "first"));
    assertThrows(
        ResponseStatusException.class, () -> bridge.register(new Registration("0.1.0", "second")));
    String requestId = bridge.requestFolder(RequestKind.FOLDER_PICK);
    bridge.claimNext(bridgeId);
    assertEquals(
        RequestStatus.CANCELLED,
        bridge
            .complete(requestId, new ResultInput(bridgeId, ResultStatus.CANCELLED, null))
            .status());
    verifyNoInteractions(workspaces);
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-07T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
