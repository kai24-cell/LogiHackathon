package jp.cheapreview.search.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** snapshot内の全チャンクで語彙とidfを固定し、選択や質問で再学習しない。 */
final class TfIdf {
  private final Map<String, Double> idf = new TreeMap<>();

  TfIdf(List<Map<String, Integer>> documents) {
    Map<String, Integer> frequencies = new TreeMap<>();
    documents.forEach(
        document -> document.keySet().forEach(term -> frequencies.merge(term, 1, Integer::sum)));
    frequencies.forEach(
        (term, frequency) ->
            idf.put(term, Math.log((documents.size() + 1.0) / (frequency + 1.0)) + 1.0));
  }

  /** tf=1+ln(count)、idf=ln((N+1)/(df+1))+1の積をL2正規化する。 */
  Map<String, Double> vector(Map<String, Integer> counts) {
    // countは正の出現回数、Nは全チャンク数、dfは語を含むチャンク数。質問だけの語は除外する。
    Map<String, Double> vector = new TreeMap<>();
    counts.forEach(
        (term, count) -> {
          if (count > 0 && idf.containsKey(term))
            vector.put(term, (1.0 + Math.log(count)) * idf.get(term));
        });
    double norm = Math.sqrt(vector.values().stream().mapToDouble(weight -> weight * weight).sum());
    // 空文書・一致なしは空ベクトルとし、ゼロ除算を起こさない。
    if (norm > 0) vector.replaceAll((term, weight) -> weight / norm);

    return Map.copyOf(vector);
  }

  /** 非負のL2正規化済みベクトルの内積を返す。範囲は0〜1、ゼロベクトルなら0。 */
  static double cosine(Map<String, Double> left, Map<String, Double> right) {
    double dot =
        left.keySet().stream()
            .sorted()
            .mapToDouble(term -> left.get(term) * right.getOrDefault(term, 0.0))
            .sum();
    return Math.max(0, Math.min(1, dot));
  }
}
