package jp.cheapreview.analysis.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.analysis.dto.JavaAnalysis.EdgeKind;
import jp.cheapreview.analysis.dto.JavaAnalysis.Resolution;
import jp.cheapreview.analysis.service.JavaStructureExtractor.MethodContext;
import jp.cheapreview.analysis.service.JavaStructureExtractor.TypeContext;

final class JavaDependencyExtractor {
  record Graph(List<JavaAnalysis.Edge> edges, List<JavaAnalysis.Unresolved> unresolved) {}

  private final List<TypeContext> types;
  private final Map<String, List<TypeContext>> byName = new LinkedHashMap<>();
  private final Map<String, List<MethodContext>> resolvedMethods = new LinkedHashMap<>();
  private final LinkedHashSet<JavaAnalysis.Edge> edges = new LinkedHashSet<>();
  private final LinkedHashSet<JavaAnalysis.Unresolved> unresolved = new LinkedHashSet<>();

  JavaDependencyExtractor(List<TypeContext> types) {
    this.types = types;
    for (TypeContext type : types) {
      byName.computeIfAbsent(type.info().qualifiedName(), ignored -> new ArrayList<>()).add(type);
      for (MethodContext method : type.methods()) {
        if (method.node() instanceof MethodDeclaration declaration) {
          try {
            resolvedMethods
                .computeIfAbsent(
                    declaration.resolve().getQualifiedSignature(), ignored -> new ArrayList<>())
                .add(method);
          } catch (RuntimeException exception) {
            // Missing external types are normal. Calls may still have a unique syntax candidate.
          }
        }
      }
    }
  }

  Graph extract() {
    for (TypeContext owner : types) {
      owner.node().findAll(ClassOrInterfaceType.class).stream()
          .filter(node -> belongsTo(node, owner))
          .forEach(node -> typeReference(owner, node, EdgeKind.TYPE_REFERENCE));
      owner.node().getFields().stream()
          .filter(
              field ->
                  field.getAnnotations().stream()
                      .anyMatch(
                          a ->
                              List.of("Autowired", "Inject", "Resource")
                                  .contains(a.getName().getIdentifier())))
          .forEach(
              field ->
                  field
                      .getVariables()
                      .forEach(
                          variable ->
                              variable
                                  .getType()
                                  .findAll(ClassOrInterfaceType.class)
                                  .forEach(node -> typeReference(owner, node, EdgeKind.FIELD_DI))));
      for (MethodContext method : owner.methods()) {
        if (method.node() instanceof ConstructorDeclaration constructor
            && (owner.node().getConstructors().size() == 1
                || constructor.getAnnotations().stream()
                    .anyMatch(
                        a ->
                            List.of("Autowired", "Inject")
                                .contains(a.getName().getIdentifier())))) {
          constructor
              .getParameters()
              .forEach(
                  parameter ->
                      parameter
                          .getType()
                          .findAll(ClassOrInterfaceType.class)
                          .forEach(node -> typeReference(owner, node, EdgeKind.CONSTRUCTOR_DI)));
        }
      }
      owner.node().findAll(MethodCallExpr.class).stream()
          .filter(node -> belongsTo(node, owner))
          .forEach(call -> methodCall(owner, call));
    }
    return new Graph(List.copyOf(edges), List.copyOf(unresolved));
  }

  private boolean belongsTo(Node node, TypeContext owner) {
    return node.findAncestor(TypeDeclaration.class).map(type -> type == owner.node()).orElse(false);
  }

  private String fromId(TypeContext owner, Node node) {
    return owner.methods().stream()
        .filter(method -> method.node().isAncestorOf(node))
        .map(method -> method.info().methodId())
        .findFirst()
        .orElse(owner.info().typeId());
  }

  private void typeReference(TypeContext owner, ClassOrInterfaceType node, EdgeKind kind) {
    try {
      String name = node.resolve().asReferenceType().getQualifiedName();
      List<TypeContext> candidates = byName.getOrDefault(name, List.of());
      if (candidates.size() == 1) {
        add(owner, node, candidates.getFirst().info().typeId(), kind, Resolution.RESOLVED);
      } else if (candidates.size() > 1) {
        missing(owner, node, kind, node.asString(), "AMBIGUOUS_TYPE");
      }
      return; // A resolved JDK/external type is deliberately not a project graph edge.
    } catch (RuntimeException exception) {
      List<TypeContext> candidates = syntaxTypes(owner, node.getNameWithScope());
      if (candidates.size() == 1) {
        add(owner, node, candidates.getFirst().info().typeId(), kind, Resolution.HEURISTIC);
      } else {
        missing(
            owner,
            node,
            kind,
            node.asString(),
            candidates.isEmpty() ? "UNRESOLVED_TYPE" : "AMBIGUOUS_TYPE");
      }
    }
  }

  private void methodCall(TypeContext owner, MethodCallExpr call) {
    try {
      var resolved = call.resolve();
      var candidates = resolvedMethods.getOrDefault(resolved.getQualifiedSignature(), List.of());
      if (candidates.size() == 1) {
        add(
            owner,
            call,
            candidates.getFirst().info().methodId(),
            EdgeKind.METHOD_CALL,
            Resolution.RESOLVED);
      } else if (byName.containsKey(resolved.declaringType().getQualifiedName())) {
        missing(
            owner,
            call,
            EdgeKind.METHOD_CALL,
            call.getNameAsString(),
            "AMBIGUOUS_OR_UNINDEXED_METHOD");
      }
      return;
    } catch (RuntimeException exception) {
      // Only use a known receiver type and a unique matching signature, never imports alone.
    }
    List<TypeContext> receivers = receiverTypes(owner, call);
    List<MethodContext> candidates =
        receivers.stream()
            .flatMap(type -> type.methods().stream())
            .filter(method -> !method.info().constructor())
            .filter(method -> method.info().name().equals(call.getNameAsString()))
            .filter(method -> signatureMatches(method, call))
            .toList();
    if (candidates.size() == 1) {
      add(
          owner,
          call,
          candidates.getFirst().info().methodId(),
          EdgeKind.METHOD_CALL,
          Resolution.HEURISTIC);
    } else {
      missing(
          owner,
          call,
          EdgeKind.METHOD_CALL,
          call.getNameAsString() + "(" + call.getArguments().size() + ")",
          candidates.size() > 1 ? "AMBIGUOUS_METHOD" : "UNRESOLVED_METHOD");
    }
  }

  private boolean signatureMatches(MethodContext method, MethodCallExpr call) {
    var parameters = method.parameters();
    if (parameters.size() != call.getArguments().size()) return false;
    for (int i = 0; i < parameters.size(); i++) {
      var parameter = parameters.get(i);
      if (parameter.isVarArgs()) return false; // Uncertain varargs conversion stays unresolved.
      String argumentType = expressionType(call.getArgument(i));
      if (argumentType == null) return false;
      if (argumentType.equals("null") && !parameter.getType().isPrimitiveType()) continue;
      String parameterType;
      try {
        parameterType = parameter.getType().resolve().describe();
      } catch (RuntimeException exception) {
        parameterType = parameter.getTypeAsString();
      }
      if (!argumentType.equals(parameterType)) return false;
    }
    return true;
  }

  private String expressionType(Expression expression) {
    try {
      return expression.calculateResolvedType().describe();
    } catch (RuntimeException exception) {
      if (expression.isNullLiteralExpr()) return "null";
      if (expression.isStringLiteralExpr()) return "java.lang.String";
      if (expression.isIntegerLiteralExpr()) return "int";
      if (expression.isBooleanLiteralExpr()) return "boolean";
      return null;
    }
  }

  private List<TypeContext> receiverTypes(TypeContext owner, MethodCallExpr call) {
    if (call.getScope().isEmpty() || call.getScope().orElseThrow().isThisExpr())
      return List.of(owner);
    Expression scope = call.getScope().orElseThrow();
    try {
      var type = scope.calculateResolvedType();
      if (type.isReferenceType())
        return byName.getOrDefault(type.asReferenceType().getQualifiedName(), List.of());
    } catch (RuntimeException exception) {
      // Recover an explicitly declared receiver type without guessing across the whole project.
    }
    if (scope.isObjectCreationExpr())
      return syntaxTypes(owner, scope.asObjectCreationExpr().getType().getNameWithScope());
    if (!scope.isNameExpr()) return List.of();
    String name = scope.asNameExpr().getNameAsString();
    Optional<CallableDeclaration<?>> callable =
        call.findAncestor(CallableDeclaration.class).map(node -> (CallableDeclaration<?>) node);
    if (callable.isPresent()) {
      var locals =
          callable.get().findAll(VariableDeclarator.class).stream()
              .filter(variable -> variable.getNameAsString().equals(name))
              .filter(
                  variable ->
                      JavaStructureExtractor.range(variable).beginLine()
                          <= JavaStructureExtractor.range(call).beginLine())
              .filter(
                  variable ->
                      variable
                          .findAncestor(BlockStmt.class)
                          .map(block -> block.isAncestorOf(call))
                          .orElse(false))
              .toList();
      if (locals.size() == 1) return syntaxTypes(owner, locals.getFirst().getTypeAsString());
      if (locals.size() > 1) return List.of();
      var parameters =
          callable.get().getParameters().stream()
              .filter(p -> p.getNameAsString().equals(name))
              .toList();
      if (parameters.size() == 1)
        return syntaxTypes(owner, parameters.getFirst().getTypeAsString());
    }
    var fields = owner.info().fields().stream().filter(field -> field.name().equals(name)).toList();
    if (fields.size() == 1) return syntaxTypes(owner, fields.getFirst().type());
    return List.of(); // Unresolved static receivers are not inferred from an import alone.
  }

  private List<TypeContext> syntaxTypes(TypeContext owner, String name) {
    String rawName = name.replaceAll("<.*>", "").replace("[]", "");
    if (byName.containsKey(rawName)
        && (rawName.contains(".") || owner.unit().getPackageDeclaration().isEmpty()))
      return byName.get(rawName);
    var explicitImports =
        owner.unit().getImports().stream()
            .filter(
                i ->
                    !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(rawName))
            .flatMap(i -> byName.getOrDefault(i.getNameAsString(), List.of()).stream())
            .toList();
    if (!explicitImports.isEmpty()) return explicitImports;
    String packageName =
        owner.unit().getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
    var samePackage = byName.getOrDefault(packageName + rawName, List.of());
    if (!samePackage.isEmpty()) return samePackage;
    return owner.unit().getImports().stream()
        .filter(i -> !i.isStatic() && i.isAsterisk())
        .flatMap(i -> byName.getOrDefault(i.getNameAsString() + "." + rawName, List.of()).stream())
        .distinct()
        .toList();
  }

  private void add(
      TypeContext owner, Node node, String target, EdgeKind kind, Resolution resolution) {
    edges.add(
        new JavaAnalysis.Edge(
            fromId(owner, node),
            target,
            kind,
            resolution == Resolution.RESOLVED ? 1.0 : 0.5,
            resolution,
            owner.relativePath(),
            JavaStructureExtractor.range(node).beginLine()));
  }

  private void missing(
      TypeContext owner, Node node, EdgeKind kind, String reference, String reason) {
    unresolved.add(
        new JavaAnalysis.Unresolved(
            fromId(owner, node),
            kind,
            reference,
            reason,
            owner.relativePath(),
            JavaStructureExtractor.range(node).beginLine()));
  }
}
