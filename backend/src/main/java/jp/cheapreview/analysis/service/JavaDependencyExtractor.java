package jp.cheapreview.analysis.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            // 外部依存の未取得は通常の状態。明示された型と署名だけで後から推定する。
          }
        }
      }
    }
  }

  /** 固定済みASTから型参照・DI・呼出しを抽出し、解決不能な参照も記録する。 */
  Graph extract() {
    for (TypeContext owner : types) {
      owner.node().findAll(ClassOrInterfaceType.class).stream()
          .filter(node -> belongsTo(node, owner))
          .forEach(node -> typeReference(owner, node, EdgeKind.TYPE_REFERENCE));

      extractFieldInjection(owner);
      extractConstructorInjection(owner);

      owner.node().findAll(MethodCallExpr.class).stream()
          .filter(node -> belongsTo(node, owner))
          .forEach(call -> methodCall(owner, call));
    }

    return new Graph(List.copyOf(edges), List.copyOf(unresolved));
  }

  private void extractFieldInjection(TypeContext owner) {
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
  }

  private void extractConstructorInjection(TypeContext owner) {
    for (MethodContext method : owner.methods()) {
      if (method.node() instanceof ConstructorDeclaration constructor
          && (owner.node().getConstructors().size() == 1
              || constructor.getAnnotations().stream()
                  .anyMatch(
                      a -> List.of("Autowired", "Inject").contains(a.getName().getIdentifier())))) {
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

  /** 明示importと矛盾するSymbolSolverの結果を採用せず、未解決のまま残す。 */
  private void typeReference(TypeContext owner, ClassOrInterfaceType node, EdgeKind kind) {
    try {
      String name = node.resolve().asReferenceType().getQualifiedName();
      if (!matchesExplicitImport(owner, node.getNameWithScope(), name)) {
        missing(owner, node, kind, node.asString(), "IMPORT_RESOLUTION_MISMATCH");
        return;
      }

      List<TypeContext> candidates = byName.getOrDefault(name, List.of());
      if (candidates.size() == 1) {
        add(owner, node, candidates.getFirst().info().typeId(), kind, Resolution.RESOLVED);
      } else if (candidates.size() > 1) {
        missing(owner, node, kind, node.asString(), "AMBIGUOUS_TYPE");
      }
      return; // 解決済みのJDK・外部型は内部グラフの辺に含めない。
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

  /** 呼出し先を採用する前に、レシーバの宣言と解決結果の整合性を確認する。 */
  private void methodCall(TypeContext owner, MethodCallExpr call) {
    var declaredReceiver = declaredReceiverTypes(owner, call);
    if (declaredReceiver.isPresent() && declaredReceiver.orElseThrow().isEmpty()) {
      // 宣言はあるが型が不明な場合、同名フィールドや別の内部型へ置き換えない。
      missing(owner, call, EdgeKind.METHOD_CALL, call.getNameAsString(), "UNRESOLVED_RECEIVER");
      return;
    }

    try {
      var resolved = call.resolve();
      var candidates = resolvedMethods.getOrDefault(resolved.getQualifiedSignature(), List.of());
      if (!byName.containsKey(resolved.declaringType().getQualifiedName())) {
        // Objectからの継承を含む外部メソッドは、内部の辺として記録しない。
        return;
      }

      if (declaredReceiver.isPresent()
          && candidates.stream()
              .noneMatch(method -> receiverContains(declaredReceiver.orElseThrow(), method))) {
        // JavaParserは終了済みのfor変数を選ぶことがある。正しい宣言からのみ再推定する。
        heuristicCall(owner, call, declaredReceiver.orElseThrow());
        return;
      }

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
      // 解決失敗時も同名候補全体へ広げず、既知のレシーバに限定する。
    }

    heuristicCall(owner, call, declaredReceiver.orElseGet(() -> receiverTypes(owner, call)));
  }

  private boolean receiverContains(List<TypeContext> receivers, MethodContext method) {
    return receivers.stream()
        .anyMatch(
            type ->
                receiverHierarchy(type, new LinkedHashSet<>()).stream()
                    .anyMatch(parent -> parent.methods().contains(method)));
  }

  /** 継承元の既知の宣言も照合し、正当な継承メソッドをスコープ不整合と誤認しない。 */
  private List<TypeContext> receiverHierarchy(TypeContext type, Set<String> visited) {
    if (!visited.add(type.info().typeId())) return List.of();

    List<TypeContext> hierarchy = new ArrayList<>();
    hierarchy.add(type);
    if (type.node().isClassOrInterfaceDeclaration()) {
      var declaration = type.node().asClassOrInterfaceDeclaration();
      List<ClassOrInterfaceType> parents = new ArrayList<>(declaration.getExtendedTypes());
      parents.addAll(declaration.getImplementedTypes());
      for (var parent : parents) {
        var candidates = syntaxTypes(type, parent.getNameWithScope());
        if (candidates.size() == 1) {
          hierarchy.addAll(receiverHierarchy(candidates.getFirst(), visited));
        }
      }
    }

    return hierarchy;
  }

  /** 同名署名の候補が一意の場合だけ推定辺にし、不明・曖昧な候補は記録する。 */
  private void heuristicCall(TypeContext owner, MethodCallExpr call, List<TypeContext> receivers) {
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
      if (parameter.isVarArgs()) return false; // 可変長引数の変換を確定できない場合は推定しない。
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
      // 型が明示されたnew式だけを構文から復元し、プロジェクト全体からは推測しない。
    }
    if (scope.isObjectCreationExpr())
      return syntaxTypes(owner, scope.asObjectCreationExpr().getType().getNameWithScope());
    return List.of(); // 未解決のstaticレシーバをimportだけから推定しない。
  }

  /** 有効な宣言を優先する。Optional内の空リストは型が不明で推定を禁止する状態。 */
  private Optional<List<TypeContext>> declaredReceiverTypes(
      TypeContext owner, MethodCallExpr call) {
    if (call.getScope().isEmpty() || !call.getScope().orElseThrow().isNameExpr()) {
      return Optional.empty();
    }

    var declaration = LexicalScopes.declaredType(call.getScope().orElseThrow().asNameExpr());
    if (declaration.isEmpty()) {
      // 宣言のない名前を終了済みの変数と誤認する解決結果も採用しない。
      var classCandidates =
          syntaxTypes(owner, call.getScope().orElseThrow().asNameExpr().getNameAsString());
      if (!classCandidates.isEmpty()) return Optional.of(classCandidates);

      try {
        var resolved = call.resolve();
        if (resolved.isStatic()
            && !byName.containsKey(resolved.declaringType().getQualifiedName())) {
          return Optional.empty();
        }
      } catch (RuntimeException exception) {
        // 未解決のstatic呼出しも、importだけでは内部の辺にしない。
      }

      return Optional.of(List.of());
    }

    // 宣言の型そのものを使う。calculateResolvedTypeも同名変数を誤認し得るため使わない。
    var type = declaration.orElseThrow();
    var declarationOwner =
        types.stream()
            .filter(
                context ->
                    type.findAncestor(TypeDeclaration.class)
                        .map(node -> node == context.node())
                        .orElse(false))
            .findFirst()
            .orElse(owner);
    var candidates = syntaxTypes(declarationOwner, type.asString());
    if (!candidates.isEmpty()) return Optional.of(candidates);

    try {
      String resolvedName = type.resolve().asReferenceType().getQualifiedName();
      String syntaxName =
          type.isClassOrInterfaceType()
              ? type.asClassOrInterfaceType().getNameWithScope()
              : type.asString();
      if (matchesExplicitImport(declarationOwner, syntaxName, resolvedName)
          && !byName.containsKey(resolvedName)) {
        // 解決済みのJDK型などは従来どおり外部参照として扱う。内部型への誤置換は通さない。
        return Optional.empty();
      }
    } catch (RuntimeException exception) {
      // 型が不明でも宣言によるシャドーイングは有効なので、外側の変数を選ばない。
    }

    return Optional.of(List.of());
  }

  /** 明示importを同一packageより優先し、未取得でも別の同名型へ進めない。 */
  private List<TypeContext> syntaxTypes(TypeContext owner, String name) {
    String rawName = name.replaceAll("<.*>", "").replace("[]", "");
    if (byName.containsKey(rawName) && rawName.contains(".")) return byName.get(rawName);
    var explicitImports =
        owner.unit().getImports().stream()
            .filter(
                i ->
                    !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(rawName))
            .toList();
    if (!explicitImports.isEmpty()) {
      // importの存在と索引の有無は別。依存を取得しない解析では空でもここで止める。
      return explicitImports.stream()
          .flatMap(i -> byName.getOrDefault(i.getNameAsString(), List.of()).stream())
          .toList();
    }

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

  private boolean matchesExplicitImport(TypeContext owner, String name, String resolvedName) {
    if (name.contains(".")) return true;

    return owner.unit().getImports().stream()
        .filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name))
        .allMatch(i -> i.getNameAsString().equals(resolvedName));
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
