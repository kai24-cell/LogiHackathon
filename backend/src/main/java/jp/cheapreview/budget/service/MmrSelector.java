package jp.cheapreview.budget.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import jp.cheapreview.budget.dto.BudgetPreview.Chunk;
import jp.cheapreview.search.service.CodeSimilarity;

/** 完全promptの増分費用を使い、必須分を保持したまま任意候補を逐次選択する。 */
final class MmrSelector {
  static final String VERSION = "mmr-incremental-tokens-v1";

  record Candidate(Chunk chunk, double relevance) {}

  record Decision(
      Candidate candidate,
      double redundancy,
      double mmr,
      long incrementalTokens,
      double priority) {}

  record Result(List<Decision> accepted, List<Candidate> rejected) {}

  @FunctionalInterface
  interface Similarity {
    double between(String first, String second);
  }

  private final double lambda;

  MmrSelector(MmrSettings settings) {
    lambda = settings.lambda();
  }

  /** 採用ごとにSと完全promptを更新し、入らない候補を飛ばして後続も確認する。 */
  Result select(
      List<Chunk> mandatory,
      List<Candidate> candidates,
      Similarity similarity,
      ToLongFunction<List<Chunk>> tokens,
      TokenEstimator estimator,
      long budget) {
    List<Chunk> selected = new ArrayList<>(mandatory);
    List<Candidate> remaining = new ArrayList<>(candidates);
    List<Decision> accepted = new ArrayList<>();
    List<Candidate> rejected = new ArrayList<>();
    long currentTokens = tokens.applyAsLong(selected);
    var redundancy = initialRedundancy(mandatory, candidates, similarity);

    while (!remaining.isEmpty()) {
      List<Decision> feasible = new ArrayList<>();
      for (var candidate : remaining) {
        var trial = new ArrayList<>(selected);
        trial.add(candidate.chunk());
        long total = tokens.applyAsLong(trial);
        if (estimator.required(total) > budget) {
          // 追加だけで本文量は単調増加するため、予算超過候補を今回の選択から除外する。
          rejected.add(candidate);
          continue;
        }
        feasible.add(
            score(candidate, redundancy.get(candidate.chunk().fileId()), total - currentTokens));
      }
      if (feasible.isEmpty()) break;

      feasible.sort(order());
      var best = feasible.getFirst();
      accepted.add(best);
      selected.add(best.candidate().chunk());
      currentTokens += best.incrementalTokens();
      remaining =
          feasible.stream()
              .skip(1)
              .map(Decision::candidate)
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      // max(S∪{best})=max(max(S),sim(best))。既計算を使い、同じ組を再計算しない。
      for (var candidate : remaining) {
        String id = candidate.chunk().fileId();
        redundancy.put(
            id,
            Math.max(
                redundancy.get(id), similarity.between(id, best.candidate().chunk().fileId())));
      }
    }
    return new Result(List.copyOf(accepted), List.copyOf(rejected));
  }

  /** 必須集合との最大cosineを初期値にし、以後は新規採用候補との比較だけで更新する。 */
  private Map<String, Double> initialRedundancy(
      List<Chunk> mandatory, List<Candidate> candidates, Similarity similarity) {
    Map<String, Double> result = new HashMap<>();
    for (var candidate : candidates) {
      double maximum =
          mandatory.stream()
              .mapToDouble(chunk -> similarity.between(candidate.chunk().fileId(), chunk.fileId()))
              .max()
              .orElse(0);
      result.put(candidate.chunk().fileId(), maximum);
    }
    return result;
  }

  /** 関連度と最大重複度を分離し、比較可能な採用時点の内訳を返す。 */
  Decision score(Candidate candidate, double redundancy, long incrementalTokens) {
    if (!Double.isFinite(candidate.relevance())
        || candidate.relevance() < 0
        || candidate.relevance() > 1
        || !Double.isFinite(redundancy)
        || redundancy < 0
        || redundancy > 1) {
      throw new IllegalArgumentException("MMR inputs must be finite and within [0, 1]");
    }
    // MMR=λR−(1−λ)max(sim)。R・cosine・λは[0,1]、MMRは[-1,1]。
    // priority=max(0,MMR)/max(ΔT,1)。ΔTは完全prompt推定tokensの差（丸め後）。
    // 関連度の係数は変更せず、λで関連度と冗長性を調整する暫定ヒューリスティック。
    // 負値も優先度0として候補に残す。増分0以下は分母1、予算は安全余裕込みで別途検証する。
    // 標準MMRそのものではなく、今後の比較実験で係数・採用方針を調整する。
    double mmr = lambda * candidate.relevance() - (1 - lambda) * redundancy;
    return new Decision(
        candidate,
        redundancy,
        mmr,
        incrementalTokens,
        Math.max(0, mmr) / Math.max(incrementalTokens, 1));
  }

  private Comparator<Decision> order() {
    return Comparator.comparingDouble(Decision::priority)
        .reversed()
        .thenComparing(Comparator.comparingDouble(Decision::mmr).reversed())
        .thenComparing(
            Comparator.comparingDouble((Decision value) -> value.candidate().relevance())
                .reversed())
        .thenComparing(value -> value.candidate().chunk().relativePath())
        .thenComparing(value -> value.candidate().chunk().fileId());
  }

  String reason(Decision decision) {
    return "MMR任意採用: "
        + VERSION
        + " / 集約 "
        + CodeSimilarity.VERSION
        + " / λ="
        + lambda
        + " / R="
        + decision.candidate().relevance()
        + " / 最大cosine="
        + decision.redundancy()
        + " / MMR="
        + decision.mmr()
        + " / 増分tokens="
        + decision.incrementalTokens()
        + " / 優先度="
        + decision.priority();
  }
}
