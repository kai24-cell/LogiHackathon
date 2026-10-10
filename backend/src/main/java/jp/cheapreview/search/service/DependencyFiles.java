package jp.cheapreview.search.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import jp.cheapreview.analysis.dto.JavaAnalysis;

/** 解析済みの方向付き辺をファイルへ投影し、探索時だけ無向化する。 */
final class DependencyFiles {
  static final int MAX_HOPS = 2;
  private final Map<String, Set<String>> neighbors = new HashMap<>();

  DependencyFiles(JavaAnalysis.Result analysis) {
    Map<String, String> nodeFiles = new HashMap<>();
    analysis
        .files()
        .forEach(
            file ->
                file.types()
                    .forEach(
                        type -> {
                          nodeFiles.put(type.typeId(), file.fileId());
                          type.methods()
                              .forEach(method -> nodeFiles.put(method.methodId(), file.fileId()));
                        }));
    // 元の解析結果は変更しない。未解決参照は辺ではなく、探索にも混ぜない。
    analysis
        .edges()
        .forEach(
            edge -> {
              String from = nodeFiles.get(edge.fromId());
              String to = nodeFiles.get(edge.toId());
              if (from != null && to != null && !from.equals(to)) {
                neighbors.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(to);
                neighbors.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(from);
              }
            });
  }

  /** 複数の主選択から最短距離を求める。訪問済み判定により循環でも停止する。 */
  Map<String, Integer> distances(Set<String> selected) {
    Map<String, Integer> distances = new HashMap<>();
    var queue = new ArrayDeque<String>();
    selected.stream()
        .sorted()
        .forEach(
            id -> {
              distances.put(id, 0);
              queue.add(id);
            });
    while (!queue.isEmpty()) {
      String current = queue.remove();
      int distance = distances.get(current);
      if (distance >= MAX_HOPS) continue;

      for (String next : neighbors.getOrDefault(current, Set.of())) {
        if (!distances.containsKey(next)) {
          distances.put(next, distance + 1);
          queue.add(next);
        }
      }
    }

    return Map.copyOf(distances);
  }

  static double score(Integer distance) {
    // Dは0〜1。自己・直接依存は1、2hopは0.5、到達不能または起点なしは0。
    return distance == null ? 0 : distance <= 1 ? 1 : distance == MAX_HOPS ? 0.5 : 0;
  }
}
