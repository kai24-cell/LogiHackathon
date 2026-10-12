package jp.cheapreview.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeSimilarityTest {
  @TempDir Path root;

  private SearchIndex index() throws Exception {
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, false));
    return new SearchIndex(capture, new JavaAnalysisService().analyze(capture));
  }

  @Test
  void centroidAveragesBeforeL2NormalizationAndEmptyVectorsStayZero() {
    var centroid = CodeSimilarity.centroid(List.of(Map.of("x", 1d), Map.of("y", 1d)));
    assertEquals(1 / Math.sqrt(2), centroid.get("x"), 1e-12);
    assertEquals(1 / Math.sqrt(2), centroid.get("y"), 1e-12);
    assertEquals(1, centroid.values().stream().mapToDouble(v -> v * v).sum(), 1e-12);
    assertTrue(CodeSimilarity.centroid(List.of()).isEmpty());
    assertTrue(CodeSimilarity.centroid(List.of(Map.of(), Map.of())).isEmpty());
    assertEquals(0, TfIdf.cosine(Map.of(), centroid));
  }

  @Test
  void omittedMethodVectorsAreNotIncludedInFileCentroid() throws Exception {
    Files.writeString(
        root.resolve("Example.java"),
        "class Example { void keep() { /* 保存 */ } void omit() { /* 配送 */ } }");
    var index = index();
    var document = index.documents.getFirst();
    var kept =
        document.chunks().stream()
            .filter(c -> c.terms().containsKey("保存"))
            .findFirst()
            .orElseThrow();
    var omitted =
        document.chunks().stream()
            .filter(c -> c.terms().containsKey("配送"))
            .findFirst()
            .orElseThrow();
    var vector =
        new CodeSimilarity(index, Map.of(document.file().fileId(), List.of(kept.methodId())));
    assertEquals(1, vector.similarity(document.file().fileId(), document.file().fileId()), 1e-12);
    var retainedOnly = CodeSimilarity.centroid(List.of(index.vectors.get(kept)));
    var full =
        CodeSimilarity.centroid(List.of(index.vectors.get(kept), index.vectors.get(omitted)));
    assertTrue(TfIdf.cosine(retainedOnly, full) < 1);
    // 別名のファイルを追加して、片方の保持集合だけ変えた実ファイル間cosineも比較する。
    Files.writeString(root.resolve("Other.java"), "class Other { void keep() { /* 保存 */ } }");
    index = index();
    document =
        index.documents.stream()
            .filter(d -> d.file().relativePath().equals("Example.java"))
            .findFirst()
            .orElseThrow();
    var other =
        index.documents.stream()
            .filter(d -> d.file().relativePath().equals("Other.java"))
            .findFirst()
            .orElseThrow();
    String keptId =
        document.chunks().stream()
            .filter(c -> c.terms().containsKey("保存"))
            .findFirst()
            .orElseThrow()
            .methodId();
    var retained =
        new CodeSimilarity(
            index,
            Map.of(
                document.file().fileId(),
                List.of(keptId),
                other.file().fileId(),
                List.of(other.chunks().getFirst().methodId())));
    var all =
        new CodeSimilarity(
            index,
            Map.of(
                document.file().fileId(),
                document.chunks().stream().map(SearchIndex.Chunk::methodId).toList(),
                other.file().fileId(),
                List.of(other.chunks().getFirst().methodId())));
    assertTrue(
        retained.similarity(document.file().fileId(), other.file().fileId())
            > all.similarity(document.file().fileId(), other.file().fileId()));
  }

  @Test
  void emptyRetainedMethodSetProducesZeroAndUnknownFilesAreRejected() throws Exception {
    Files.writeString(root.resolve("Example.java"), "class Example { void run() {} }");
    var index = index();
    String id = index.documents.getFirst().file().fileId();
    var similarity = new CodeSimilarity(index, Map.of(id, List.of()));
    assertEquals(0, similarity.similarity(id, id));
    assertThrows(
        IllegalArgumentException.class, () -> similarity.similarity(id, "not-in-snapshot"));
  }

  @Test
  void methodlessFilesUseExistingPathAndDeclarationFallback() throws Exception {
    Files.writeString(root.resolve("Example.java"), "class Example { int state; }");
    var index = index();
    var file = index.documents.getFirst();
    assertEquals(null, file.chunks().getFirst().methodId());
    assertTrue(file.chunks().getFirst().terms().containsKey("example"));
    var similarity = new CodeSimilarity(index, Map.of(file.file().fileId(), List.of()));
    assertEquals(1, similarity.similarity(file.file().fileId(), file.file().fileId()), 1e-12);
  }
}
