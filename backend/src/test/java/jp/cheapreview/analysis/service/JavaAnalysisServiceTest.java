package jp.cheapreview.analysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.analysis.dto.JavaAnalysis.EdgeKind;
import jp.cheapreview.analysis.dto.JavaAnalysis.Resolution;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceCapture;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaAnalysisServiceTest {
  @TempDir Path root;
  private final WorkspaceScanner scanner =
      new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60);
  private final JavaAnalysisService analysis = new JavaAnalysisService();

  private WorkspaceCapture capture(Path directory) throws Exception {
    return scanner.capture(directory, new Options(false, false, false));
  }

  private void write(String path, String source) throws Exception {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
  }

  @Test
  void sampleExtractsRolesOverloadsLocationsAndResolvedDependenciesDespiteBrokenFile()
      throws Exception {
    var snapshot = capture(Path.of("../samples/java-analysis"));
    var result = analysis.analyze(snapshot);
    assertEquals(7, result.files().size());
    assertEquals(
        1,
        result.files().stream()
            .filter(f -> f.parseStatus() == JavaAnalysis.ParseStatus.FAILED)
            .count());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Broken.java")));
    var service =
        result.files().stream()
            .flatMap(f -> f.types().stream())
            .filter(t -> t.qualifiedName().equals("demo.OrderService"))
            .findFirst()
            .orElseThrow();
    assertTrue(service.roles().stream().anyMatch(r -> r.name().equals("Service")));
    var overloads = service.methods().stream().filter(m -> m.name().equals("find")).toList();
    assertEquals(
        List.of("find(int)", "find(String)"),
        overloads.stream().map(JavaAnalysis.Method::signature).toList());
    assertEquals(2, overloads.stream().map(JavaAnalysis.Method::methodId).distinct().count());
    assertTrue(
        service.methods().stream().allMatch(m -> m.range().beginLine() <= m.range().endLine()));
    assertTrue(
        result.edges().stream()
            .anyMatch(
                e -> e.kind() == EdgeKind.METHOD_CALL && e.resolution() == Resolution.RESOLVED));
    assertTrue(result.edges().stream().anyMatch(e -> e.kind() == EdgeKind.CONSTRUCTOR_DI));
    assertTrue(result.edges().stream().anyMatch(e -> e.kind() == EdgeKind.FIELD_DI));
    assertTrue(
        result.unresolved().stream()
            .anyMatch(u -> u.reference().contains("UnknownSecurityBuilder")));
    assertTrue(
        result.files().stream()
            .flatMap(f -> f.types().stream())
            .flatMap(t -> t.roles().stream())
            .anyMatch(r -> r.name().equals("Security")));
    assertEquals(result, analysis.analyze(snapshot));
  }

  @Test
  void capturesOriginalSourceAndNeverLoadsExcludedOrLaterChangedFiles() throws Exception {
    write(
        "p/Visible.java",
        "package p; public class Visible { Hidden hidden; public int value() { return 1; } }");
    write("target/p/Hidden.java", "package p; public class Hidden {}");
    var snapshot = capture(root);
    Files.writeString(root.resolve("p/Visible.java"), "class Changed {}");
    var result = analysis.analyze(snapshot);
    assertEquals(1, result.files().size());
    var type = result.files().getFirst().types().getFirst();
    assertEquals("p.Visible", type.qualifiedName());
    assertEquals("{ return 1; }", type.methods().getFirst().body());
    assertTrue(result.unresolved().stream().anyMatch(u -> u.reference().equals("Hidden")));
    assertTrue(result.edges().isEmpty());
    assertEquals("class Changed {}", Files.readString(root.resolve("p/Visible.java")));
  }

  @Test
  void stableIdsDistinguishNestedTypesAndRetainInclusiveLinePositions() throws Exception {
    write(
        "p/Example.java",
        "package p;\npublic class Example {\n  public void run() {\n  }\n  class Nested { void run() {} }\n}\n");
    var first = analysis.analyze(capture(root));
    var types = first.files().getFirst().types();
    assertEquals(2, types.size());
    assertEquals(new JavaAnalysis.Range(3, 4), types.getFirst().methods().getFirst().range());
    assertFalse(
        types
            .getFirst()
            .methods()
            .getFirst()
            .methodId()
            .equals(types.get(1).methods().getFirst().methodId()));
    write(
        "p/Example.java",
        "package p;\npublic class Example {\n  public void run() { System.out.println(1); }\n  class Nested { void run() {} }\n}\n");
    var second = analysis.analyze(capture(root));
    assertEquals(
        types.getFirst().methods().getFirst().methodId(),
        second.files().getFirst().types().getFirst().methods().getFirst().methodId());
  }

  @Test
  void importsAloneDoNotCreateEdgesAndAmbiguousReceiversAreNotGuessed() throws Exception {
    write("p/Unused.java", "package p; public class Unused {}");
    write(
        "p/Caller.java",
        "package p; import p.Unused; public class Caller { void go() { missing.ping(); } }");
    var result = analysis.analyze(capture(root));
    assertTrue(result.edges().isEmpty());
    assertTrue(result.unresolved().stream().anyMatch(u -> u.kind() == EdgeKind.METHOD_CALL));
  }

  @Test
  void syntaxFallbackRequiresExplicitReceiverAndUniqueSignature() {
    String source =
        "class Receiver { void ping() {} void pick(String s) {} void pick(Integer i) {} } "
            + "class Caller { Receiver receiver; void go() { receiver.ping(); receiver.pick(null); other.ping(); } }";
    var unit = new JavaParser().parse(source).getResult().orElseThrow();
    var contexts = new JavaStructureExtractor().extract("Example.java", source, unit);
    var graph = new JavaDependencyExtractor(contexts).extract();
    assertEquals(
        1,
        graph.edges().stream()
            .filter(e -> e.kind() == EdgeKind.METHOD_CALL && e.resolution() == Resolution.HEURISTIC)
            .count());
    assertTrue(graph.unresolved().stream().anyMatch(u -> u.reason().equals("AMBIGUOUS_METHOD")));
    assertTrue(graph.unresolved().stream().anyMatch(u -> u.reason().equals("UNRESOLVED_METHOD")));
  }

  @Test
  void recordCompactConstructorAndOriginalCrLfBodyAreRetained() throws Exception {
    write(
        "p/Value.java",
        "package p;\r\npublic record Value(String name) {\r\n  public Value {\r\n    if (name == null) throw new IllegalArgumentException();\r\n  }\r\n}\r\n");
    var type = analysis.analyze(capture(root)).files().getFirst().types().getFirst();
    assertEquals("RECORD", type.kind());
    assertEquals("name", type.fields().getFirst().name());
    var constructor = type.methods().getFirst();
    assertTrue(constructor.constructor());
    assertEquals("Value(String)", constructor.signature());
    assertEquals(new JavaAnalysis.Range(3, 5), constructor.range());
    assertEquals(
        "{\r\n    if (name == null) throw new IllegalArgumentException();\r\n  }",
        constructor.body());
  }

  @Test
  void resolvesAcrossMultipleSourceRootsWithoutLoadingProjectDependencies() throws Exception {
    write(
        "first/src/main/java/one/Caller.java",
        "package one; import two.Target; public class Caller { Target target; public void call() { target.run(); } }");
    write(
        "second/src/main/java/two/Target.java",
        "package two; public class Target { public void run() {} }");
    var result = analysis.analyze(capture(root));
    var target =
        result.files().stream()
            .flatMap(f -> f.types().stream())
            .filter(t -> t.qualifiedName().equals("two.Target"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        result.edges().stream()
            .anyMatch(
                e ->
                    e.kind() == EdgeKind.METHOD_CALL
                        && e.resolution() == Resolution.RESOLVED
                        && e.toId().equals(target.methods().getFirst().methodId())));
  }
}
