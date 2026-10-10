package jp.cheapreview.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.search.dto.CodeSearch;
import jp.cheapreview.search.dto.CodeSearch.Mode;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceCapture;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class CodeSearchServiceTest {
  @TempDir Path root;
  private final WorkspaceScanner scanner =
      new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60);
  private final CodeSearchService service =
      new CodeSearchService(new JavaAnalysisService(), new SearchScoring(0.5, 0.3, 0.2), 1);

  private WorkspaceCapture capture(Path directory) throws Exception {
    return scanner.capture(directory, new Options(false, false, false));
  }

  private void write(String path, String text) throws Exception {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void fixedQuestionsRankSemanticallyExpectedFilesNearTop() throws Exception {
    Path sample = Path.of("../samples/code-search");
    var capture = capture(sample);
    var expectations =
        new ObjectMapper().readTree(Files.readString(sample.resolve("expected-searches.json")));
    for (var expected : expectations) {
      List<String> selected = new java.util.ArrayList<>();
      for (var path : expected.get("selected"))
        selected.add(
            capture.snapshot().files().stream()
                .filter(file -> file.relativePath().equals(path.asText()))
                .findFirst()
                .orElseThrow()
                .fileId());
      var request =
          new CodeSearch.Request(
              expected.get("question").asText(),
              selected,
              Mode.valueOf(expected.get("mode").asText()));
      var result = service.search("sample", capture, request);
      var top =
          result.candidates().stream().limit(3).map(CodeSearch.Candidate::relativePath).toList();
      for (var path : expected.get("expectedTopThree"))
        assertTrue(top.contains(path.asText()), result.toString());
      assertEquals(result, service.search("sample", capture, request));
      for (var candidate : result.candidates()) {
        var score = candidate.score();
        assertEquals(
            score.total(),
            score.cosineContribution() + score.dependencyContribution() + score.roleContribution());
      }
    }
  }

  @Test
  void selectionControlsTwoHopSearchDespiteCyclesAndNoVocabularyMatch() throws Exception {
    write("A.java", "class A { B b; }");
    write("B.java", "class B { A a; C c; }");
    write("C.java", "class C { D d; }");
    write("D.java", "class D {}");
    var capture = capture(root);
    String a =
        capture.snapshot().files().stream()
            .filter(file -> file.relativePath().equals("A.java"))
            .findFirst()
            .orElseThrow()
            .fileId();
    var result =
        service.search(
            "w", capture, new CodeSearch.Request("無関係な質問", List.of(a), Mode.CLASS_EXPLAIN));
    assertEquals(
        List.of("A.java", "B.java", "C.java", "D.java"),
        result.candidates().stream().map(CodeSearch.Candidate::relativePath).toList());
    assertEquals(
        List.of(1.0, 1.0, 0.5, 0.0),
        result.candidates().stream().map(c -> c.score().dependency()).toList());
    assertTrue(result.candidates().getFirst().primarySelected());
    assertTrue(result.candidates().stream().allMatch(c -> c.score().cosine() == 0));
    var noSelection =
        service.search(
            "w", capture, new CodeSearch.Request("無関係な質問", List.of(), Mode.CLASS_EXPLAIN));
    assertTrue(
        noSelection.candidates().stream()
            .allMatch(c -> c.dependencyDistance() == null && c.score().total() == 0));
    assertFalse(noSelection.candidates().stream().anyMatch(CodeSearch.Candidate::primarySelected));
    String d =
        capture.snapshot().files().stream()
            .filter(file -> file.relativePath().equals("D.java"))
            .findFirst()
            .orElseThrow()
            .fileId();
    var reverse =
        service.search(
            "w", capture, new CodeSearch.Request("無関係な質問", List.of(d), Mode.CLASS_EXPLAIN));
    assertEquals("D.java", reverse.candidates().getFirst().relativePath());
    assertEquals("C.java", reverse.candidates().get(1).relativePath());
    assertEquals(noSelection.documentCount(), result.documentCount());
  }

  @Test
  void rejectsBlankQuestionsAndForeignSelectionsAndHandlesEmptySnapshot() throws Exception {
    var capture = capture(root);
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.search(
                "w", capture, new CodeSearch.Request(" \n　", List.of(), Mode.SERVICE_REVIEW)));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.search(
                "w",
                capture,
                new CodeSearch.Request("質問", List.of("foreign"), Mode.SERVICE_REVIEW)));
    assertThrows(
        ResponseStatusException.class,
        () -> service.search("w", capture, new CodeSearch.Request("質問", List.of(), null)));
    assertTrue(
        service
            .search("w", capture, new CodeSearch.Request("質問", List.of(), Mode.SERVICE_REVIEW))
            .candidates()
            .isEmpty());
  }

  @Test
  void fixedSnapshotSurvivesEditsWithoutReadingExcludedOrNewFiles() throws Exception {
    write("Visible.java", "class Visible { // 保存処理\n void save() {} }");
    write("target/Hidden.java", "class Hidden { // 保存処理\n void save() {} }");
    var captured = capture(root);
    write("Visible.java", "class Changed {}");
    write("Later.java", "class Later { // 保存処理\n void save() {} }");
    var result =
        service.search(
            "w", captured, new CodeSearch.Request("保存処理", List.of(), Mode.PROJECT_STRUCTURE));
    assertEquals(1, result.candidates().size());
    assertEquals("Visible.java", result.candidates().getFirst().relativePath());
    assertTrue(result.candidates().getFirst().score().cosine() > 0);
    var newSnapshot =
        service.search(
            "w", capture(root), new CodeSearch.Request("保存処理", List.of(), Mode.PROJECT_STRUCTURE));
    assertEquals(2, newSnapshot.candidates().size());
    assertFalse(newSnapshot.snapshotId().equals(result.snapshotId()));
  }

  @Test
  void changingSelectionDoesNotChangeIdfOrCosine() throws Exception {
    var capture = capture(Path.of("../samples/code-search"));
    var first =
        service.search(
            "w", capture, new CodeSearch.Request("注文を保存する", List.of(), Mode.SERVICE_REVIEW));
    var selected = first.candidates().getFirst().fileId();
    var second =
        service.search(
            "w",
            capture,
            new CodeSearch.Request("注文を保存する", List.of(selected), Mode.SERVICE_REVIEW));
    for (var candidate : first.candidates()) {
      var changed =
          second.candidates().stream()
              .filter(c -> c.fileId().equals(candidate.fileId()))
              .findFirst()
              .orElseThrow();
      assertEquals(candidate.score().cosine(), changed.score().cosine());
    }
    assertEquals(first.documentCount(), second.documentCount());
  }
}
