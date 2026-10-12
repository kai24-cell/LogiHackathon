package jp.cheapreview.budget.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.budget.dto.BudgetPreview.Request;
import jp.cheapreview.search.dto.CodeSearch.Mode;
import jp.cheapreview.search.service.CodeSearchService;
import jp.cheapreview.search.service.SearchScoring;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MmrPreviewIntegrationTest {
  @TempDir Path root;

  @Test
  void mmrKeepsPrimaryCallCompletionAndNeverReadsConfigurationOrExcludedSource() throws Exception {
    Files.writeString(
        root.resolve("Main.java"),
        """
        class Main {
          Other other;
          public void run() { first(); }
          private void first() { second(); }
          private void second() { third(); }
          private void third() { int omittedBody = 42; }
        }
        """);
    Files.writeString(root.resolve("Other.java"), "class Other { public void run() { /* 保存 */ } }");
    Files.writeString(root.resolve("application.properties"), "password=FAKE_MMR_SECRET_SENTINEL");
    Files.createDirectory(root.resolve("target"));
    Files.writeString(
        root.resolve("target/Hidden.java"),
        "class Hidden { String data = \"FAKE_MMR_SECRET_SENTINEL\"; }");
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, true));
    String id =
        capture.snapshot().files().stream()
            .filter(file -> file.relativePath().equals("Main.java"))
            .findFirst()
            .orElseThrow()
            .fileId();
    var request =
        new Request(
            "w",
            capture.snapshot().snapshotId(),
            "保存の処理を確認",
            Mode.SERVICE_REVIEW,
            List.of(id),
            100000,
            "unknown",
            null,
            null,
            1);
    for (double lambda : new double[] {0, .5, 1}) {
      var settings = TokenEstimatorTest.settings(Map.of());
      var estimator = new TokenEstimator(settings);
      var analysis = new JavaAnalysisService();
      var search = new CodeSearchService(analysis, new SearchScoring(.5, .3, .2), 1);
      var service =
          new BudgetPreviewService(
              settings, estimator, analysis, search, Clock.systemUTC(), new MmrSettings(lambda));
      var preview = service.preview(capture, request);
      assertTrue(preview.canExecute());
      var primary =
          preview.selectedChunks().stream()
              .filter(chunk -> chunk.fileId().equals(id))
              .findFirst()
              .orElseThrow();
      assertTrue(primary.mandatory());
      assertTrue(primary.code().contains("private void first() { second(); }"));
      assertTrue(primary.code().contains("private void second() { third(); }"));
      assertFalse(primary.code().contains("omittedBody = 42"));
      assertTrue(preview.selectedChunks().stream().anyMatch(chunk -> !chunk.mandatory()));
      assertTrue(
          preview.selectedChunks().stream()
              .filter(chunk -> !chunk.mandatory())
              .allMatch(
                  chunk -> chunk.reasons().stream().anyMatch(reason -> reason.contains("MMR"))));
      assertFalse(preview.promptMaterial().contains("FAKE_MMR_SECRET_SENTINEL"));
      assertFalse(preview.promptMaterial().contains("Hidden"));
      assertTrue(
          preview.selectedChunks().stream()
              .noneMatch(chunk -> chunk.relativePath().equals("application.properties")));
      assertEquals(
          estimator.estimate(preview.promptMaterial()).tokens(), preview.counts().tokens());
      assertTrue(preview.minimumBudgetTokens() <= request.inputBudgetTokens());
    }
  }
}
