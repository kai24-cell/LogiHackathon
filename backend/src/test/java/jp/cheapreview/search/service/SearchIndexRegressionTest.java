package jp.cheapreview.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.search.dto.CodeSearch;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchIndexRegressionTest {
  @TempDir Path root;

  private SearchIndex index(String source) throws Exception {
    Files.writeString(root.resolve("Example.java"), source);
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, false));
    return new SearchIndex(capture, new JavaAnalysisService().analyze(capture));
  }

  @Test
  void adjacentMethodsDoNotShareBodyComments() throws Exception {
    var index =
        index(
            """
        class Example {
          void archiveOrderTransactionSynchronously() { /* 保存 */ }
          void send() {}
        }
        """);
    var chunks = index.documents.getFirst().chunks();
    assertEquals(1, chunks.getFirst().terms().get("保存"));
    assertFalse(chunks.get(1).terms().containsKey("保存"));
  }

  @Test
  void sameLineMethodsAndJavadocKeepTheirOwnComments() throws Exception {
    var index =
        index(
            """
        class Example {
          void first() { /* 保存 */ } void second() { /* 配送 */ }
          /** 認証 */
          void third() {}
        }
        """);
    var chunks = index.documents.getFirst().chunks();
    assertEquals(1, chunks.getFirst().terms().get("保存"));
    assertFalse(chunks.getFirst().terms().containsKey("配送"));
    assertEquals(1, chunks.get(1).terms().get("配送"));
    assertFalse(chunks.get(1).terms().containsKey("保存"));
    assertEquals(1, chunks.get(2).terms().get("認証"));
  }

  @Test
  void fallbackChunksCountEachCommentOnce() throws Exception {
    var index = index("// 注文保存\nclass Example { String key; }");
    var terms = index.documents.getFirst().chunks().getFirst().terms();
    assertEquals(1, terms.get("注文"));
    assertEquals(1, terms.get("文保"));
    assertEquals(1, terms.get("保存"));

    var malformed = index("// 注文保存\nclass Example { String key; broken }");
    var malformedTerms = malformed.documents.getFirst().chunks().getFirst().terms();
    // 構文エラーでASTが回復できた場合にも、コメントを重複して加えない。
    assertTrue(malformedTerms.getOrDefault("保存", 0) <= 1);
  }

  @Test
  void fieldAnnotationsParticipateInSearch() throws Exception {
    Files.writeString(
        root.resolve("Example.java"), "class Example { @Autowired Object repository; }");
    var capture =
        new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60)
            .capture(root, new Options(false, false, false));
    var result =
        new CodeSearchService(new JavaAnalysisService(), new SearchScoring(0.5, 0.3, 0.2), 1)
            .search(
                "w",
                capture,
                new CodeSearch.Request(
                    "Autowired の注入箇所", List.of(), CodeSearch.Mode.SERVICE_REVIEW));
    assertTrue(result.candidates().getFirst().matchingTerms().contains("autowired"));
    assertTrue(result.candidates().getFirst().score().cosine() > 0);
  }
}
