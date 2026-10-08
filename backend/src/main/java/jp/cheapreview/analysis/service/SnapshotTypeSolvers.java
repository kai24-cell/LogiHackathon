package jp.cheapreview.analysis.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.cache.Cache;
import com.github.javaparser.resolution.cache.CacheStats;
import com.github.javaparser.symbolsolver.cache.GuavaCache;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.google.common.cache.CacheBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Supplies JavaParserTypeSolver with closed caches: a miss never reads another source file. */
final class SnapshotTypeSolvers {
  private SnapshotTypeSolvers() {}

  static void add(
      CombinedTypeSolver solver, JavaParser parser, Path root, Map<String, CompilationUnit> units) {
    var files = new ClosedCache<Path, Optional<CompilationUnit>>(Optional.empty());
    var directories = new ClosedCache<Path, List<CompilationUnit>>(List.of());
    Map<Path, List<CompilationUnit>> byDirectory = new HashMap<>();
    var sourceRoots = new java.util.TreeSet<Path>();
    units.forEach(
        (relativePath, unit) -> {
          Path path = root.resolve(relativePath).toAbsolutePath().normalize();
          files.put(path, Optional.of(unit));
          byDirectory.computeIfAbsent(path.getParent(), ignored -> new ArrayList<>()).add(unit);
          Path sourceRoot = path.getParent();
          String packageName =
              unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
          if (!packageName.isEmpty()) {
            String[] names = packageName.split("\\.");
            for (int i = names.length - 1; i >= 0; i--) {
              if (sourceRoot.getFileName() == null
                  || !sourceRoot.getFileName().toString().equals(names[i])) {
                sourceRoot = root;
                break;
              }
              sourceRoot = sourceRoot.getParent();
            }
          }
          sourceRoots.add(sourceRoot.startsWith(root) ? sourceRoot : root);
        });
    byDirectory.forEach((path, values) -> directories.put(path, List.copyOf(values)));
    for (Path sourceRoot : sourceRoots) {
      solver.add(
          new JavaParserTypeSolver(
              sourceRoot,
              parser,
              files,
              directories,
              new GuavaCache<>(CacheBuilder.newBuilder().build())));
    }
  }

  private static final class ClosedCache<K, V> implements Cache<K, V> {
    private final Map<K, V> entries = new HashMap<>();
    private final V empty;
    private final Cache<K, V> statistics = new GuavaCache<>(CacheBuilder.newBuilder().build());

    private ClosedCache(V empty) {
      this.empty = empty;
    }

    public void put(K key, V value) {
      entries.put(key, value);
    }

    public Optional<V> get(K key) {
      return Optional.of(entries.getOrDefault(key, empty));
    }

    public void remove(K key) {
      entries.remove(key);
    }

    public void removeAll() {
      entries.clear();
    }

    public boolean contains(K key) {
      return true;
    }

    public long size() {
      return entries.size();
    }

    public boolean isEmpty() {
      return entries.isEmpty();
    }

    public CacheStats stats() {
      return statistics.stats();
    }
  }
}
