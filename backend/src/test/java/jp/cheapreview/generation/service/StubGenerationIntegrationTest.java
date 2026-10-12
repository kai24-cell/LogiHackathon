package jp.cheapreview.generation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jp.cheapreview.budget.dto.BudgetPreview;
import jp.cheapreview.budget.service.BudgetPreviewService;
import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.generation.dto.SettingsDtos;
import jp.cheapreview.search.dto.CodeSearch.Mode;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
    properties = {
      "cheapreview.generation.provider=stub",
      "cheapreview.connection-token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
class StubGenerationIntegrationTest {
  @Autowired ApplicationContext context;
  @Autowired WorkspaceService workspaces;
  @Autowired BudgetPreviewService budgets;
  @Autowired GenerationService generation;
  @Autowired GenerationSettings settings;
  @TempDir Path root;

  @Test
  void fixedProviderHasNoHttpClientAndUsesNormalBudgetSchemaAndReferences() throws Exception {
    assertTrue(context.getBeansOfType(GeminiHttpClient.class).isEmpty());
    assertFalse(settings.status().configured());
    var configured = settings.useStub();
    Files.writeString(root.resolve("OrderService.java"), "class OrderService { void save() {} }");
    String workspace = workspaces.register(root.toUri().toString()).workspaceId();
    String scan = workspaces.startScan(workspace, new Options(false, false, false));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (workspaces.getJob(scan).state()
            == jp.cheapreview.workspace.dto.WorkspaceDtos.JobState.QUEUED
        || workspaces.getJob(scan).state()
            == jp.cheapreview.workspace.dto.WorkspaceDtos.JobState.SCANNING) {
      if (System.nanoTime() > deadline) fail("Scan timeout");
      Thread.sleep(10);
    }
    var snapshot = workspaces.getSnapshot(workspace);
    var input =
        new BudgetPreview.Request(
            workspace,
            snapshot.snapshotId(),
            "保存処理を確認したい",
            Mode.SERVICE_REVIEW,
            List.of(snapshot.files().getFirst().fileId()),
            8192,
            configured.modelId(),
            null,
            null,
            1);
    var preview = budgets.preview(workspaces.getCapture(workspace, snapshot.snapshotId()), input);
    assertTrue(preview.canExecute());
    var start =
        new GenerationDtos.Start(
            preview.previewId(), UUID.randomUUID().toString(), configured.version(), input);
    var started = generation.start(start);
    assertEquals(started, generation.start(start));
    GenerationDtos.Job job;
    do {
      job = generation.get(started.jobId());
      if (System.nanoTime() > deadline) fail("Generation timeout");
      Thread.sleep(10);
    } while (job.state() == GenerationDtos.State.QUEUED
        || job.state() == GenerationDtos.State.GENERATING
        || job.state() == GenerationDtos.State.TRANSLATING);
    assertEquals(GenerationDtos.State.TRANSLATION_FAILED, job.state());
    assertEquals("TRANSLATION_NOT_READY", job.translation().errorCode());
    assertEquals(SettingsDtos.Source.STUB, job.result().source());
    assertEquals(
        List.of("ref-1"), job.result().answerEnglish().sections().getFirst().referenceIds());
    assertEquals("OrderService.java", job.result().references().getFirst().relativePath());
    assertNull(job.result().usage().totalTokens());
  }
}
