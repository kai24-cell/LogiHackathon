package jp.cheapreview.budget.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.budget.dto.BudgetPreview.Request;
import jp.cheapreview.search.dto.CodeSearch.Mode;
import jp.cheapreview.search.service.CodeSearchService;
import jp.cheapreview.search.service.SearchScoring;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceCapture;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class BudgetPreviewServiceTest {
  @TempDir Path root;
  final BudgetSettings settings = TokenEstimatorTest.settings(Map.of());
  final TokenEstimator estimator = new TokenEstimator(settings);
  final MutableClock clock = new MutableClock();
  final JavaAnalysisService analysis = new JavaAnalysisService();
  final BudgetPreviewService service =
      new BudgetPreviewService(
          settings,
          estimator,
          analysis,
          new CodeSearchService(analysis, new SearchScoring(.5, .3, .2), 1),
          clock);

  private WorkspaceCapture capture(boolean config) throws Exception {
    return new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
        .capture(root, new Options(false, false, config));
  }

  private Request request(WorkspaceCapture capture, List<String> ids, long budget) {
    return new Request(
        "w",
        capture.snapshot().snapshotId(),
        "保存の処理を確認",
        Mode.SERVICE_REVIEW,
        ids,
        budget,
        "unknown-model",
        null,
        null,
        1);
  }

  private String id(WorkspaceCapture capture, String path) {
    return capture.snapshot().files().stream()
        .filter(file -> file.relativePath().equals(path))
        .findFirst()
        .orElseThrow()
        .fileId();
  }

  @Test
  void exactlyEstimatedBudgetFailsButMarginBoundaryPassesWithoutTruncatingPrimary()
      throws Exception {
    Files.writeString(
        root.resolve("Main.java"),
        """
        class Main {
          // 保存
          public String save() { return "%s"; }
        }
        """
            .formatted("x".repeat(5000)));
    var capture = capture(false);
    var high =
        service.preview(capture, request(capture, List.of(id(capture, "Main.java")), 100000));
    long minimum = high.minimumBudgetTokens();
    var exactEstimate =
        service.preview(
            capture, request(capture, List.of(id(capture, "Main.java")), high.counts().tokens()));
    assertFalse(exactEstimate.canExecute());
    var tooSmallRequest = request(capture, List.of(id(capture, "Main.java")), minimum - 1);
    var tooSmall = service.preview(capture, tooSmallRequest);
    assertFalse(tooSmall.canExecute());
    assertEquals(high.selectedChunks(), tooSmall.selectedChunks());
    assertEquals(
        422,
        assertThrows(
                ResponseStatusException.class,
                () -> service.check(tooSmall.previewId(), capture, tooSmallRequest))
            .getStatusCode()
            .value());
    var boundaryRequest = request(capture, List.of(id(capture, "Main.java")), minimum);
    var boundary = service.preview(capture, boundaryRequest);
    assertTrue(boundary.canExecute());
    assertEquals(boundary, service.check(boundary.previewId(), capture, boundaryRequest));
    assertNull(boundary.estimatedCost());
    assertEquals(estimator.estimate(boundary.promptMaterial()), boundary.counts());
  }

  @Test
  void selectionChangesMandatoryEstimateAndDuplicatesAreNotCountedTwice() throws Exception {
    Files.writeString(root.resolve("A.java"), "class A {}");
    Files.writeString(
        root.resolve("B.java"), "class B { String value = \"" + "x".repeat(2000) + "\"; }");
    var capture = capture(false);
    String a = id(capture, "A.java"), b = id(capture, "B.java");
    var one = service.preview(capture, request(capture, List.of(a), 100000));
    var both = service.preview(capture, request(capture, List.of(b, a, a), 100000));
    var repeat = service.preview(capture, request(capture, List.of(a, b), 100000));
    assertTrue(both.mandatoryPromptTokens() > one.mandatoryPromptTokens());
    assertEquals(2, both.selectedChunks().size());
    assertEquals(both.selectedChunks(), repeat.selectedChunks());
    assertEquals(both.counts(), repeat.counts());
    assertEquals(both.requestDigest(), repeat.requestDigest());
  }

  @Test
  void staleInputBudgetModelAndRescanCannotValidateOldPreview() throws Exception {
    Files.writeString(root.resolve("A.java"), "class A {}");
    Files.writeString(root.resolve("B.java"), "class B {}");
    var captured = capture(false);
    var req = request(captured, List.of(id(captured, "A.java")), 8192);
    var result = service.preview(captured, req);
    for (var changed :
        List.of(
            new Request(
                "w",
                req.snapshotId(),
                "別の質問",
                req.mode(),
                req.selectedFileIds(),
                8192,
                req.modelId(),
                null,
                null,
                1),
            request(captured, List.of(id(captured, "B.java")), 8192),
            request(captured, req.selectedFileIds(), 8193),
            new Request(
                "w",
                req.snapshotId(),
                req.question(),
                req.mode(),
                req.selectedFileIds(),
                8192,
                "different-model",
                null,
                null,
                1))) {
      assertEquals(
          409,
          assertThrows(
                  ResponseStatusException.class,
                  () -> service.check(result.previewId(), captured, changed))
              .getStatusCode()
              .value());
    }
    Files.writeString(root.resolve("A.java"), "class A { int changed; }");
    var rescanned = capture(false);
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.check(
                result.previewId(),
                rescanned,
                request(rescanned, List.of(id(rescanned, "A.java")), 8192)));
    clock.now = clock.now.plusSeconds(300);
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () -> service.check(result.previewId(), captured, req))
            .getStatusCode()
            .value());
  }

  @Test
  void invalidBudgetUnknownSelectionAndTargetAreRejected() throws Exception {
    Files.writeString(root.resolve("A.java"), "class A {}");
    var capture = capture(false);
    var ids = List.of(id(capture, "A.java"));
    // 設計の暫定範囲は両端を含む。予算内に収まるかどうかの判定とは分けて確認する。
    for (long budget : new long[] {1024, 100000})
      service.preview(capture, request(capture, ids, budget));
    for (long budget : new long[] {-1, 0, 1023, 100001, Long.MAX_VALUE})
      assertThrows(
          ResponseStatusException.class,
          () -> service.preview(capture, request(capture, ids, budget)));
    assertThrows(
        ResponseStatusException.class,
        () -> service.preview(capture, request(capture, List.of(), 8192)));
    assertThrows(
        ResponseStatusException.class,
        () -> service.preview(capture, request(capture, List.of("foreign"), 8192)));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.preview(
                capture,
                new Request(
                    "w",
                    capture.snapshot().snapshotId(),
                    "質問",
                    Mode.CLASS_EXPLAIN,
                    ids,
                    8192,
                    "unknown",
                    null,
                    null,
                    1)));
  }

  @Test
  void configSecretsAndExcludedFilesAreNeverReadIntoPrompt() throws Exception {
    Files.writeString(root.resolve("A.java"), "class A {}");
    Files.writeString(root.resolve("application.properties"), "password=FAKE_SECRET_SENTINEL");
    Files.createDirectory(root.resolve("target"));
    Files.writeString(
        root.resolve("target/Hidden.java"),
        "class Hidden { String secret = \"FAKE_SECRET_SENTINEL\"; }");
    var capture = capture(true);
    var normal = service.preview(capture, request(capture, List.of(id(capture, "A.java")), 8192));
    assertFalse(normal.promptMaterial().contains("FAKE_SECRET_SENTINEL"));
    assertFalse(normal.promptMaterial().contains("Hidden"));
    var config =
        service.preview(
            capture, request(capture, List.of(id(capture, "application.properties")), 8192));
    assertFalse(config.canExecute());
    assertTrue(
        config.excludedCandidates().stream().anyMatch(file -> file.reason().contains("秘密情報")));
    assertFalse(config.promptMaterial().contains("FAKE_SECRET_SENTINEL"));
  }

  @Test
  void largeCandidateIsSkippedAndLaterSmallerCandidateStillFits() throws Exception {
    Files.writeString(root.resolve("Main.java"), "class Main { Large large; }");
    Files.writeString(
        root.resolve("Large.java"),
        "@Service class Large { public String load() { return \"" + "x".repeat(2000) + "\"; } }");
    Files.writeString(
        root.resolve("Small.java"),
        "@Repository class Small { public String load() { return \""
            + "x".repeat(1200)
            + "\"; } }");
    var capture = capture(false);
    var highRequest = request(capture, List.of(id(capture, "Main.java")), 100000);
    var high = service.preview(capture, highRequest);
    assertEquals("Large.java", high.selectedChunks().get(1).relativePath());
    var large =
        high.selectedChunks().stream()
            .filter(chunk -> chunk.relativePath().equals("Large.java"))
            .findFirst()
            .orElseThrow();
    var small =
        high.selectedChunks().stream()
            .filter(chunk -> chunk.relativePath().equals("Small.java"))
            .findFirst()
            .orElseThrow();
    var primary =
        high.selectedChunks().stream().filter(chunk -> chunk.mandatory()).findFirst().orElseThrow();
    // Largeは直接依存Service(R=.5)、Smallは非依存Repository(R=.1)。Largeのdensityが先。
    assertTrue(
        .5 / estimator.estimate(large.code()).tokens()
            > .1 / estimator.estimate(small.code()).tokens());
    long budget =
        estimator.required(
            estimator
                .estimate(PromptMaterializer.materialize(highRequest, List.of(primary, small)))
                .tokens());
    assertTrue(
        estimator.required(
                estimator
                    .estimate(PromptMaterializer.materialize(highRequest, List.of(primary, large)))
                    .tokens())
            > budget);
    var result =
        service.preview(capture, request(capture, List.of(id(capture, "Main.java")), budget));
    assertTrue(result.canExecute());
    assertTrue(
        result.selectedChunks().stream()
            .anyMatch(chunk -> chunk.relativePath().equals("Small.java")));
    assertTrue(
        result.excludedCandidates().stream()
            .anyMatch(chunk -> chunk.relativePath().equals("Large.java")));
    assertTrue(result.minimumBudgetTokens() <= budget);
    assertEquals(
        result.counts().tokens(),
        result.mandatoryPromptTokens() + result.relatedAdditionalTokens());
  }

  @Test
  void knownContextLimitReservesOutputAndRejectsLargerInputBudget() throws Exception {
    Files.writeString(root.resolve("A.java"), "class A {}");
    var capture = capture(false);
    var priced =
        TokenEstimatorTest.settings(
            Map.of(
                "fictional",
                new BudgetSettings.Price(
                    java.math.BigDecimal.ONE,
                    java.math.BigDecimal.ONE,
                    "USD",
                    java.time.LocalDate.of(2026, 10, 10),
                    8192L)));
    var pricedService =
        new BudgetPreviewService(
            priced,
            new TokenEstimator(priced),
            analysis,
            new CodeSearchService(analysis, new SearchScoring(.5, .3, .2), 1),
            clock);
    var req =
        new Request(
            "w",
            capture.snapshot().snapshotId(),
            "質問",
            Mode.SERVICE_REVIEW,
            List.of(id(capture, "A.java")),
            6144,
            "fictional",
            null,
            null,
            1);
    assertTrue(pricedService.preview(capture, req).canExecute());
    var tooLarge =
        new Request(
            "w",
            req.snapshotId(),
            req.question(),
            req.mode(),
            req.selectedFileIds(),
            6145,
            req.modelId(),
            null,
            null,
            1);
    assertEquals(
        400,
        assertThrows(ResponseStatusException.class, () -> pricedService.preview(capture, tooLarge))
            .getStatusCode()
            .value());
  }

  private static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-10T00:00:00Z");

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
