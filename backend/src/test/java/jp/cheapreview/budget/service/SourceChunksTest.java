package jp.cheapreview.budget.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.budget.dto.BudgetPreview.Range;
import jp.cheapreview.budget.dto.BudgetPreview.Request;
import jp.cheapreview.search.dto.CodeSearch.Mode;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceChunksTest {
  @TempDir Path root;

  @Test
  void primaryPublicSeedsKeepTwoLevelsOfPrivateCallsAndDeclarations() throws Exception {
    Files.writeString(
        root.resolve("A.java"),
        """
        package example;
        class A {
          int state;
          A() { state = 1; }
          public void run() { first(); }
          private void first() { second(); }
          private void second() { third(); }
          private void third() { state = 99; }
        }
        """);
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, false));
    var analysis = new JavaAnalysisService().analyze(capture);
    var file = analysis.files().getFirst();
    var request =
        new Request(
            "w",
            capture.snapshot().snapshotId(),
            "無関係",
            Mode.SERVICE_REVIEW,
            List.of(file.fileId()),
            8192,
            "unknown",
            null,
            null,
            1);
    var chunk =
        new SourceChunks(analysis, Map.of())
            .build(file, capture.javaSources().get("A.java"), request, true, 3);
    assertTrue(chunk.code().contains("package example;"));
    assertTrue(chunk.code().contains("A() { state = 1; }"));
    assertTrue(chunk.code().contains("private void first() { second(); }"));
    assertTrue(chunk.code().contains("private void second() { third(); }"));
    assertFalse(chunk.code().contains("private void third() { state = 99; }"));
    assertEquals(List.of(new Range(8, 8)), chunk.omittedRanges());
  }

  @Test
  void classExplainKeepsAllSignaturesAndExplicitTargetBodyWithoutCutting() throws Exception {
    Files.writeString(
        root.resolve("A.java"),
        """
        class A {
          public void a() { int one = 1; }
          public void b() { int two = 2; }
          public void c() { int three = 3; }
          private void target() { int four = 4; }
          private void omitted() { int five = 5; }
        }
        """);
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, false));
    var analysis = new JavaAnalysisService().analyze(capture);
    var file = analysis.files().getFirst();
    String target =
        file.types().getFirst().methods().stream()
            .filter(method -> method.name().equals("target"))
            .findFirst()
            .orElseThrow()
            .methodId();
    var request =
        new Request(
            "w",
            capture.snapshot().snapshotId(),
            "説明",
            Mode.CLASS_EXPLAIN,
            List.of(file.fileId()),
            8192,
            "unknown",
            file.fileId(),
            target,
            1);
    var chunk =
        new SourceChunks(analysis, Map.of())
            .build(file, capture.javaSources().get("A.java"), request, true, 3);
    assertTrue(chunk.methodIds().contains(target));
    assertTrue(chunk.code().contains("private void target() { int four = 4; }"));
    assertTrue(chunk.code().contains("private void omitted()"));
    assertFalse(chunk.code().contains("int five = 5"));
    assertTrue(chunk.code().contains("body omitted"));
  }

  @Test
  void authenticationPrimaryKeepsEveryConfigurationMethod() throws Exception {
    Files.writeString(
        root.resolve("SecurityConfig.java"),
        """
        @EnableWebSecurity
        class SecurityConfig {
          void one() {}
          void two() {}
          void three() {}
          void four() {}
          void five() {}
        }
        """);
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, false));
    var analysis = new JavaAnalysisService().analyze(capture);
    var file = analysis.files().getFirst();
    var request =
        new Request(
            "w",
            capture.snapshot().snapshotId(),
            "認証",
            Mode.AUTH_ANALYSIS,
            List.of(file.fileId()),
            8192,
            "unknown",
            null,
            null,
            1);
    var chunk =
        new SourceChunks(analysis, Map.of())
            .build(file, capture.javaSources().get("SecurityConfig.java"), request, true, 3);
    assertEquals(5, chunk.methodIds().size());
    assertTrue(chunk.omittedRanges().isEmpty());
  }

  @Test
  void overlappingAndAdjacentRangesMergeWithoutDuplicatingLines() {
    assertEquals(
        List.of(new Range(1, 8), new Range(10, 12)),
        SourceChunks.merge(
            List.of(new Range(4, 6), new Range(1, 4), new Range(6, 8), new Range(10, 12))));
  }

  @Test
  void privateInterfaceHelpersAreNotImplicitlyPublicSeeds() throws Exception {
    Files.writeString(
        root.resolve("Example.java"),
        """
        interface Example {
          private void helper1() {}
          private void helper2() {}
          private void helper3() {}
          default void run() {}
        }
        """);
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, false));
    var analysis = new JavaAnalysisService().analyze(capture);
    var file = analysis.files().getFirst();
    var request =
        new Request(
            "w",
            capture.snapshot().snapshotId(),
            "無関係",
            Mode.SERVICE_REVIEW,
            List.of(file.fileId()),
            8192,
            "unknown",
            null,
            null,
            1);
    var chunk =
        new SourceChunks(analysis, Map.of())
            .build(file, capture.javaSources().get("Example.java"), request, true, 3);
    String run =
        file.types().getFirst().methods().stream()
            .filter(method -> method.name().equals("run"))
            .findFirst()
            .orElseThrow()
            .methodId();
    assertEquals(List.of(run), chunk.methodIds());
    assertTrue(chunk.code().contains("default void run() {}"));
    assertFalse(chunk.code().contains("private void helper1"));
  }

  @Test
  void closingBraceOnMethodLineDoesNotLeaveATruncatedMethod() throws Exception {
    Files.writeString(
        root.resolve("A.java"),
        """
        class A {
          private void omitted() {
            int important = 42;
          } }
        """);
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, false));
    var analysis = new JavaAnalysisService().analyze(capture);
    var file = analysis.files().getFirst();
    var request =
        new Request(
            "w",
            capture.snapshot().snapshotId(),
            "無関係",
            Mode.SERVICE_REVIEW,
            List.of(file.fileId()),
            8192,
            "unknown",
            null,
            null,
            1);
    var chunk =
        new SourceChunks(analysis, Map.of())
            .build(file, capture.javaSources().get("A.java"), request, true, 3);
    assertTrue(chunk.code().contains("private void omitted()"));
    assertTrue(chunk.code().contains("int important = 42;"));
    assertEquals(1, chunk.methodIds().size());
    assertTrue(chunk.omittedRanges().isEmpty());
  }
}
