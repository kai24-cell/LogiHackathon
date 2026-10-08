package jp.cheapreview.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jp.cheapreview.workspace.dto.WorkspaceDtos.JobState;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class WorkspaceServiceTest {
  @TempDir Path root;

  @Test
  void admitsOneScanAndPublishesSnapshotBeforeSuccess() throws Exception {
    WorkspaceScanner scanner = mock(WorkspaceScanner.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Snapshot snapshot = new Snapshot("snapshot", List.of(), List.of());
    when(scanner.scan(any(), any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return snapshot;
            });
    WorkspaceService service = new WorkspaceService(scanner, 20, 100, 2);
    try {
      String workspaceId = service.register(root.toUri().toString()).workspaceId();
      Options options = new Options(false, false, false);
      String jobId = service.startScan(workspaceId, options);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertThrows(ResponseStatusException.class, () -> service.startScan(workspaceId, options));
      release.countDown();
      org.awaitility.Awaitility.await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> service.getJob(jobId).state() == JobState.SUCCEEDED);
      assertEquals(snapshot, service.getSnapshot(workspaceId));
    } finally {
      release.countDown();
      service.close();
    }
  }
}
