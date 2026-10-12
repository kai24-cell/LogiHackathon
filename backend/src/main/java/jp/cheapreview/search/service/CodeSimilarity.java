package jp.cheapreview.search.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 保存済み索引の保持メソッドだけを集約した送信候補間の類似度。 */
public final class CodeSimilarity {
  public static final String VERSION = "retained-method-centroid-l2-v1";
  private final Map<String, Map<String, Double>> vectors;

  CodeSimilarity(SearchIndex index, Map<String, List<String>> retainedMethods) {
    Map<String, Map<String, Double>> aggregates = new TreeMap<>();
    for (var document : index.documents) {
      var retained = retainedMethods.get(document.file().fileId());
      if (retained == null) continue;

      // 本文を省略したメソッドは重複度に混ぜない。メソッドなしは既存のファイル索引を使う。
      var methodVectors =
          document.chunks().stream()
              .filter(chunk -> chunk.methodId() == null || retained.contains(chunk.methodId()))
              .sorted(
                  java.util.Comparator.comparing(
                      chunk -> chunk.methodId() == null ? "" : chunk.methodId()))
              .map(index.vectors::get)
              .toList();
      aggregates.put(document.file().fileId(), centroid(methodVectors));
    }
    vectors = Map.copyOf(aggregates);
  }

  /** 平均ベクトルをL2正規化する。空ベクトルのcosineは0とする。 */
  static Map<String, Double> centroid(List<Map<String, Double>> methods) {
    if (methods.isEmpty()) return Map.of();
    Map<String, Double> mean = new TreeMap<>();
    for (var vector : methods) {
      vector.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> mean.merge(entry.getKey(), entry.getValue() / methods.size(), Double::sum));
    }
    double norm = Math.sqrt(mean.values().stream().mapToDouble(value -> value * value).sum());
    if (norm == 0) return Map.of();
    mean.replaceAll((term, value) -> value / norm);
    return Map.copyOf(mean);
  }

  public double similarity(String firstFileId, String secondFileId) {
    if (!vectors.containsKey(firstFileId) || !vectors.containsKey(secondFileId)) {
      throw new IllegalArgumentException("Unknown selection candidate");
    }
    return TfIdf.cosine(vectors.get(firstFileId), vectors.get(secondFileId));
  }
}
