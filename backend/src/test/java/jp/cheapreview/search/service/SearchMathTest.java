package jp.cheapreview.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.search.dto.CodeSearch.Mode;
import jp.cheapreview.workspace.dto.WorkspaceDtos.SourceFile;
import org.junit.jupiter.api.Test;

class SearchMathTest {
  private SearchIndex.Document roleDocument(Set<String> roles, boolean userRelated) {
    var type =
        new JavaAnalysis.Type(
            "t",
            "Example",
            "CLASS",
            new JavaAnalysis.Range(1, 1),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    var analysis =
        new JavaAnalysis.File(
            "f",
            "Example.java",
            JavaAnalysis.ParseStatus.PARSED,
            "",
            List.of(),
            List.of(type),
            List.of());
    return new SearchIndex.Document(
        new SourceFile("f", "Example.java", "JAVA", "hash", 0),
        analysis,
        List.of(),
        roles,
        userRelated);
  }

  @Test
  void roleFitFollowsEachDesignModeAndDoesNotGuessFromQuestion() {
    var service = roleDocument(Set.of("Service"), false);
    var controller = roleDocument(Set.of("Controller"), false);
    var ordinary = roleDocument(Set.of(), false);
    assertEquals(1, RoleFit.score(Mode.SERVICE_REVIEW, service, false, null));
    assertEquals(0.5, RoleFit.score(Mode.SERVICE_REVIEW, controller, false, null));
    assertEquals(0, RoleFit.score(Mode.SERVICE_REVIEW, ordinary, false, null));
    assertEquals(1, RoleFit.score(Mode.CLASS_EXPLAIN, ordinary, true, 0));
    assertEquals(0.5, RoleFit.score(Mode.CLASS_EXPLAIN, ordinary, false, 2));
    assertEquals(0, RoleFit.score(Mode.CLASS_EXPLAIN, ordinary, false, null));
    assertEquals(1, RoleFit.score(Mode.PROJECT_STRUCTURE, service, false, null));
    assertEquals(0.5, RoleFit.score(Mode.PROJECT_STRUCTURE, ordinary, false, null));
    assertEquals(
        1, RoleFit.score(Mode.AUTH_ANALYSIS, roleDocument(Set.of("Security"), false), false, null));
    assertEquals(0.5, RoleFit.score(Mode.AUTH_ANALYSIS, controller, false, null));
    assertEquals(
        0.5, RoleFit.score(Mode.AUTH_ANALYSIS, roleDocument(Set.of("Service"), true), false, null));
    assertEquals(0, RoleFit.score(Mode.AUTH_ANALYSIS, service, false, null));
  }

  @Test
  void termsRetainIdentifiersAndUseUnicodeJapaneseBigrams() {
    var terms = SearchTerms.counts("OrderHTTPClient user_account 注文保存 𠮷野 一");
    assertTrue(
        terms
            .keySet()
            .containsAll(
                List.of(
                    "orderhttpclient",
                    "order",
                    "http",
                    "client",
                    "user_account",
                    "user",
                    "account",
                    "注文",
                    "文保",
                    "保存",
                    "𠮷野",
                    "一")));
  }

  @Test
  void usesDocumentFrequencyLogTfAndL2Normalization() {
    var model = new TfIdf(List.of(Map.of("a", 2, "b", 1), Map.of("b", 1)));
    var vector = model.vector(Map.of("a", 2, "b", 1, "not-in-snapshot", 100));
    double a = (1 + Math.log(2)) * (1 + Math.log(3.0 / 2));
    double norm = Math.sqrt(a * a + 1);
    assertEquals(a / norm, vector.get("a"), 1e-12);
    assertEquals(1 / norm, vector.get("b"), 1e-12);
    assertEquals(2, vector.size());
    assertEquals(1, TfIdf.cosine(vector, vector), 1e-12);
    assertEquals(0, TfIdf.cosine(Map.of(), vector));
    assertTrue(model.vector(Map.of("unknown", 1)).isEmpty());
    assertTrue(new TfIdf(List.of()).vector(Map.of("a", 1)).isEmpty());
  }

  @Test
  void contributionsFollowDesignFormulaAndRejectInvalidConfiguration() {
    var scoring = new SearchScoring(0.5, 0.3, 0.2);
    var score = scoring.score(0.4, 1, 0.5);
    assertEquals(0.6, score.total(), 1e-12);
    assertEquals(
        score.total(),
        score.cosineContribution() + score.dependencyContribution() + score.roleContribution());
    assertThrows(IllegalArgumentException.class, () -> new SearchScoring(-0.1, 0.6, 0.5));
    assertThrows(IllegalArgumentException.class, () -> new SearchScoring(0.5, 0.5, 0.5));
    assertThrows(IllegalArgumentException.class, () -> new SearchScoring(Double.NaN, 0.3, 0.2));
    assertThrows(IllegalArgumentException.class, () -> scoring.score(Double.NaN, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> scoring.score(0, 2, 0));
  }
}
