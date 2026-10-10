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
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JavaAnalysisReviewRegressionTest {
  @TempDir Path root;

  private void write(String path, String source) throws Exception {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
  }

  private JavaAnalysis.Result analyze() throws Exception {
    var scanner = new WorkspaceScanner(1048576, 100000, 10000, 104857600, 60);
    return new JavaAnalysisService()
        .analyze(scanner.capture(root, new Options(false, false, false)));
  }

  private JavaAnalysis.Type type(JavaAnalysis.Result result, String name) {
    return result.files().stream()
        .flatMap(file -> file.types().stream())
        .filter(type -> type.qualifiedName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private List<JavaAnalysis.Edge> calls(JavaAnalysis.Result result) {
    return result.edges().stream().filter(edge -> edge.kind() == EdgeKind.METHOD_CALL).toList();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "java.util.function.Consumer<External> cb = receiver -> receiver.ping();",
        "java.util.function.Consumer<External> cb = (External receiver) -> receiver.ping();",
        "try { throw new External(); } catch (External receiver) { receiver.ping(); }",
        "{ External receiver = null; receiver.ping(); }"
      })
  void unresolvedShadowingDeclarationNeverFallsBackToField(String body) throws Exception {
    write(
        "p/Caller.java",
        "package p; class A { void ping() {} } class Caller { A receiver; void run() { "
            + body
            + " } }");

    var result = analyze();

    assertTrue(calls(result).isEmpty());
    assertTrue(
        result.unresolved().stream()
            .anyMatch(
                item ->
                    item.kind() == EdgeKind.METHOD_CALL
                        && item.reason().equals("UNRESOLVED_RECEIVER")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "for (B receiver = null; receiver != null;) { receiver.ping(); } receiver.ping();",
        "for (B receiver : new B[0]) { receiver.ping(); } receiver.ping();",
        "{ B receiver = null; receiver.ping(); } receiver.ping();"
      })
  void endedScopesDoNotReplaceFieldAndActiveScopesStillResolve(String body) throws Exception {
    write(
        "p/Caller.java",
        "package p; class A { void ping() {} } class B { void ping() {} } class Caller { A receiver; void run() { "
            + body
            + " } }");

    var result = analyze();
    String fieldMethod = type(result, "p.A").methods().getFirst().methodId();
    String localMethod = type(result, "p.B").methods().getFirst().methodId();

    assertEquals(2, calls(result).size());
    assertEquals(
        List.of(localMethod, fieldMethod),
        calls(result).stream().map(JavaAnalysis.Edge::toId).toList());
    assertTrue(result.unresolved().stream().noneMatch(item -> item.kind() == EdgeKind.METHOD_CALL));
  }

  @Test
  void forScopeIsAlsoRespectedWithoutSymbolSolver() {
    String source =
        "class A { void ping() {} } class B { void ping() {} } class Caller { A receiver; void run() { for (B receiver = null; receiver != null;) { receiver.ping(); } receiver.ping(); } }";
    var unit = new JavaParser().parse(source).getResult().orElseThrow();
    var contexts = new JavaStructureExtractor().extract("Caller.java", source, unit);
    var graph = new JavaDependencyExtractor(contexts).extract();
    var methodCalls =
        graph.edges().stream().filter(edge -> edge.kind() == EdgeKind.METHOD_CALL).toList();

    assertEquals(2, methodCalls.size());
    assertEquals(
        contexts.get(1).methods().getFirst().info().methodId(), methodCalls.getFirst().toId());
    assertEquals(
        contexts.getFirst().methods().getFirst().info().methodId(), methodCalls.get(1).toId());
  }

  @Test
  void endedForVariableWithoutOuterDeclarationStaysUnresolved() throws Exception {
    write(
        "Caller.java",
        "class B { void ping() {} } class Caller { void run() { for (B receiver = null; receiver != null;) {} receiver.ping(); } }");

    var result = analyze();

    assertTrue(calls(result).isEmpty());
    assertTrue(result.unresolved().stream().anyMatch(item -> item.kind() == EdgeKind.METHOD_CALL));
  }

  @Test
  void validInheritedAndJdkCallsAreNotRejectedByReceiverValidation() throws Exception {
    write(
        "Caller.java",
        "class Base { void ping() {} } class Child extends Base {} class Caller { Child receiver; void run() { receiver.ping(); receiver.toString(); Math.abs(1); } }");

    var result = analyze();

    assertEquals(1, calls(result).size());
    assertEquals(
        type(result, "Base").methods().getFirst().methodId(), calls(result).getFirst().toId());
    assertTrue(result.unresolved().stream().noneMatch(item -> item.kind() == EdgeKind.METHOD_CALL));
  }

  @Test
  void missingExplicitImportDoesNotResolveToSamePackageType() throws Exception {
    write("p/Target.java", "package p; class Target { void ping() {} }");
    write(
        "p/Caller.java",
        "package p; import external.Target; class Caller { Target receiver; void run() { receiver.ping(); } }");

    var result = analyze();

    assertTrue(result.edges().isEmpty());
    assertTrue(
        result.unresolved().stream().anyMatch(item -> item.kind() == EdgeKind.TYPE_REFERENCE));
    assertTrue(result.unresolved().stream().anyMatch(item -> item.kind() == EdgeKind.METHOD_CALL));
  }

  @Test
  void missingExplicitImportAlsoBlocksSyntaxFallback() {
    String source =
        "package p; import external.Target; class Caller { Target receiver; void run() { receiver.ping(); } }";
    String target = "package p; class Target { void ping() {} }";
    var extractor = new JavaStructureExtractor();
    var contexts =
        new java.util.ArrayList<>(
            extractor.extract(
                "p/Caller.java", source, new JavaParser().parse(source).getResult().orElseThrow()));
    contexts.addAll(
        extractor.extract(
            "p/Target.java", target, new JavaParser().parse(target).getResult().orElseThrow()));

    var graph = new JavaDependencyExtractor(contexts).extract();

    assertTrue(graph.edges().isEmpty());
    assertTrue(
        graph.unresolved().stream().anyMatch(item -> item.kind() == EdgeKind.TYPE_REFERENCE));
    assertTrue(graph.unresolved().stream().anyMatch(item -> item.kind() == EdgeKind.METHOD_CALL));
  }

  @Test
  void availableExplicitImportWinsOverSamePackageType() throws Exception {
    write("p/Target.java", "package p; class Target { void ping() {} }");
    write("other/Target.java", "package other; public class Target { public void ping() {} }");
    write(
        "p/Caller.java",
        "package p; import other.Target; class Caller { Target receiver; void run() { receiver.ping(); } }");

    var result = analyze();

    assertEquals(1, calls(result).size());
    assertEquals(
        type(result, "other.Target").methods().getFirst().methodId(),
        calls(result).getFirst().toId());
  }

  @Test
  void sameLineLocalTypesHaveUniqueStableIdsInDifferentScopes() throws Exception {
    String source =
        "class Outer { void a() { class Local { void run() {} } } void b() { class Local { void run() {} } } void c() { { class Local { void run() {} } } { class Local { void run() {} } } } }";
    write("Outer.java", source);
    var first = analyze().files().getFirst().types();

    assertEquals(5, first.size());
    assertEquals(5, first.stream().map(JavaAnalysis.Type::typeId).distinct().count());
    var localMethods = first.stream().skip(1).flatMap(type -> type.methods().stream()).toList();
    assertEquals(4, localMethods.stream().map(JavaAnalysis.Method::methodId).distinct().count());

    write("Outer.java", "\n" + source.replace("void run() {}", "void run() { int value = 1; }"));
    var second = analyze().files().getFirst().types();
    assertEquals(
        first.stream().map(JavaAnalysis.Type::typeId).toList(),
        second.stream().map(JavaAnalysis.Type::typeId).toList());
    assertEquals(
        localMethods.stream().map(JavaAnalysis.Method::methodId).toList(),
        second.stream()
            .skip(1)
            .flatMap(type -> type.methods().stream())
            .map(JavaAnalysis.Method::methodId)
            .toList());
  }

  @Test
  void repositoryRoleIncludesImplementsAndRetainsExtendsEvidence() throws Exception {
    write(
        "p/Repositories.java",
        "package p; abstract class CustomRepository implements JpaRepository<Entity, Long> {} interface OtherRepository extends CrudRepository<Entity, Long> {} class Ordinary {} ");

    var result = analyze();

    assertTrue(
        type(result, "p.CustomRepository").roles().stream()
            .anyMatch(
                role ->
                    role.name().equals("Repository")
                        && role.evidence().equals("implements:JpaRepository")));
    assertTrue(
        type(result, "p.OtherRepository").roles().stream()
            .anyMatch(
                role ->
                    role.name().equals("Repository")
                        && role.evidence().equals("extends:CrudRepository")));
    assertTrue(type(result, "p.Ordinary").roles().isEmpty());
  }

  @Test
  void securityEvidenceBelongsOnlyToItsDeclaringType() throws Exception {
    write(
        "Container.java",
        "class Container { class SecurityConfig { SecurityFilterChain chain; } } class DirectSecurity { SecurityFilterChain chain; }");

    var result = analyze();

    assertFalse(
        type(result, "Container").roles().stream()
            .anyMatch(role -> role.name().equals("Security")));
    assertTrue(
        type(result, "Container.SecurityConfig").roles().stream()
            .anyMatch(role -> role.name().equals("Security")));
    assertTrue(
        type(result, "DirectSecurity").roles().stream()
            .anyMatch(role -> role.name().equals("Security")));
  }
}
