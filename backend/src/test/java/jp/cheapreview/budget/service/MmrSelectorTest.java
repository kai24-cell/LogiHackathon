package jp.cheapreview.budget.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import jp.cheapreview.budget.dto.BudgetPreview.Chunk;
import org.junit.jupiter.api.Test;

class MmrSelectorTest {
  private final TokenEstimator estimator =
      new TokenEstimator(TokenEstimatorTest.settings(Map.of()));

  private Chunk chunk(String id, boolean mandatory) {
    return new Chunk(id, id + ".java", mandatory, List.of(), List.of(), List.of(), id, List.of());
  }

  private MmrSelector.Candidate candidate(String id, double relevance) {
    return new MmrSelector.Candidate(chunk(id, false), relevance);
  }

  @Test
  void lambdaEndpointsAndMidpointKeepRelevanceAndRedundancyDistinct() {
    var candidate = candidate("a", .8);
    var relevanceOnly = new MmrSelector(new MmrSettings(1)).score(candidate, .6, 10);
    var balanced = new MmrSelector(new MmrSettings(.5)).score(candidate, .6, 10);
    var diversityOnly = new MmrSelector(new MmrSettings(0)).score(candidate, .6, 10);
    assertEquals(.8, relevanceOnly.mmr(), 1e-12);
    assertEquals(.08, relevanceOnly.priority(), 1e-12);
    assertEquals(.1, balanced.mmr(), 1e-12);
    assertEquals(.01, balanced.priority(), 1e-12);
    assertEquals(-.6, diversityOnly.mmr(), 1e-12);
    assertEquals(0, diversityOnly.priority());
    assertEquals(.4, new MmrSelector(new MmrSettings(.5)).score(candidate, 0, 0).priority(), 1e-12);
  }

  @Test
  void settingsRejectNonFiniteAndOutOfRangeLambda() {
    for (double lambda : new double[] {-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> new MmrSettings(lambda));
    }
  }

  @Test
  void primaryRemainsInEveryPromptAndZeroSimilarityOrdersByRelevance() {
    var primary = chunk("primary", true);
    ToLongFunction<List<Chunk>> tokens =
        selected -> {
          assertEquals(primary, selected.getFirst());
          return selected.size() * 100;
        };
    var result =
        new MmrSelector(new MmrSettings(.5))
            .select(
                List.of(primary),
                List.of(candidate("low", .2), candidate("high", .9)),
                (a, b) -> 0,
                tokens,
                estimator,
                5000);
    assertEquals(
        List.of("high", "low"),
        result.accepted().stream().map(d -> d.candidate().chunk().fileId()).toList());
    assertTrue(result.accepted().stream().allMatch(d -> d.redundancy() == 0));
  }

  @Test
  void acceptingAFileRecomputesRedundancyForRemainingFiles() {
    var result =
        new MmrSelector(new MmrSettings(.5))
            .select(
                List.of(chunk("primary", true)),
                List.of(
                    candidate("first", 1), candidate("duplicate", .9), candidate("different", .6)),
                (a, b) -> a.equals("duplicate") && b.equals("first") ? 1 : 0,
                selected -> selected.size() * 100,
                estimator,
                5000);
    assertEquals(
        List.of("first", "different", "duplicate"),
        result.accepted().stream().map(d -> d.candidate().chunk().fileId()).toList());
    var duplicate = result.accepted().getLast();
    assertEquals(1, duplicate.redundancy());
    assertEquals(-.05, duplicate.mmr(), 1e-12);
    assertEquals(0, duplicate.priority());
    assertTrue(result.rejected().isEmpty());
  }

  @Test
  void oversizedHighRelevanceDoesNotBlockSmallerCandidateAtExactMarginBoundary() {
    var small = candidate("small", .2);
    var large = candidate("large", 1);
    var result =
        new MmrSelector(new MmrSettings(1))
            .select(
                List.of(chunk("primary", true)),
                List.of(large, small),
                (a, b) -> 0,
                selected ->
                    selected.stream().anyMatch(c -> c.fileId().equals("large"))
                        ? 1000
                        : selected.size() * 100,
                estimator,
                estimator.required(200));
    assertEquals(
        List.of(small), result.accepted().stream().map(MmrSelector.Decision::candidate).toList());
    assertEquals(List.of(large), result.rejected());
    var insufficient =
        new MmrSelector(new MmrSettings(1))
            .select(
                List.of(chunk("primary", true)),
                List.of(small),
                (a, b) -> 0,
                selected -> selected.size() * 100,
                estimator,
                estimator.required(200) - 1);
    assertTrue(insufficient.accepted().isEmpty());
    assertEquals(List.of(small), insufficient.rejected());
  }

  @Test
  void tiesAreStableAcrossInputOrderIncludingFileIdTieBreaker() {
    var a = candidate("a", .5);
    var b = candidate("b", .5);
    var selector = new MmrSelector(new MmrSettings(1));
    var first =
        selector.select(
            List.of(),
            List.of(b, a),
            (x, y) -> 0,
            selected -> selected.size() * 100,
            estimator,
            5000);
    var second =
        selector.select(
            List.of(),
            List.of(a, b),
            (x, y) -> 0,
            selected -> selected.size() * 100,
            estimator,
            5000);
    assertEquals(first.accepted(), second.accepted());
    assertEquals("a", first.accepted().getFirst().candidate().chunk().fileId());
    var samePath =
        new MmrSelector.Candidate(
            new Chunk("z", "a.java", false, List.of(), List.of(), List.of(), "", List.of()), .5);
    var tied =
        selector.select(
            List.of(),
            List.of(samePath, a),
            (x, y) -> 0,
            selected -> selected.size() * 100,
            estimator,
            5000);
    assertEquals("a", tied.accepted().getFirst().candidate().chunk().fileId());
  }

  @Test
  void promptIncrementAndPriorityAreRecomputedAfterEachAcceptance() {
    // 初回の共通付随情報と採用後の追記を含む実prompt推定を使う。単体コード長では選ばない。
    ToLongFunction<List<Chunk>> tokens =
        selected -> {
          String material = "共通指示";
          for (var value : selected)
            material += "\nFILE " + value.relativePath() + "\n" + value.code();
          return estimator.estimate(material).tokens();
        };
    var selector = new MmrSelector(new MmrSettings(1));
    var primary = chunk("primary", true);
    var result =
        selector.select(
            List.of(primary),
            List.of(candidate("a", .8), candidate("b", .8)),
            (a, b) -> 0,
            tokens,
            estimator,
            5000);
    var selected = new java.util.ArrayList<>(List.of(primary));
    long previous = tokens.applyAsLong(selected);
    for (var decision : result.accepted()) {
      selected.add(decision.candidate().chunk());
      long current = tokens.applyAsLong(selected);
      assertEquals(current - previous, decision.incrementalTokens());
      assertEquals(.8 / Math.max(current - previous, 1), decision.priority(), 1e-12);
      previous = current;
    }
    assertEquals(2, result.accepted().size());
  }

  @Test
  void eachCandidatePairIsComparedOnlyOnceWhileMaximumRedundancyIsPreserved() {
    var comparisons = new java.util.HashSet<String>();
    var result =
        new MmrSelector(new MmrSettings(.5))
            .select(
                List.of(chunk("primary", true)),
                List.of(
                    candidate("first", 1), candidate("duplicate", .9), candidate("different", .6)),
                (a, b) -> {
                  assertTrue(comparisons.add(a + ":" + b), "同じcosineの再計算");
                  return a.equals("duplicate") && b.equals("first") ? 1 : 0;
                },
                selected -> selected.size() * 100,
                estimator,
                5000);
    assertEquals(
        List.of("first", "different", "duplicate"),
        result.accepted().stream().map(d -> d.candidate().chunk().fileId()).toList());
    assertEquals(6, comparisons.size()); // 必須との3組＋任意候補間の3組。
    assertEquals(1, result.accepted().getLast().redundancy());
  }
}
