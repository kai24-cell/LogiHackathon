package jp.cheapreview.analysis.service;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.List;
import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.analysis.dto.JavaAnalysis.Range;

final class JavaStructureExtractor {
  record MethodContext(JavaAnalysis.Method info, Node node, List<Parameter> parameters) {}

  record TypeContext(
      JavaAnalysis.Type info,
      TypeDeclaration<?> node,
      CompilationUnit unit,
      String relativePath,
      List<MethodContext> methods) {}

  /** 原文と位置を保持した型・メソッドを抽出し、同一ファイル内の宣言を識別する。 */
  List<TypeContext> extract(String relativePath, String source, CompilationUnit unit) {
    List<TypeContext> contexts = new ArrayList<>();
    SourceText original = new SourceText(source);
    for (TypeDeclaration<?> declaration : unit.findAll(TypeDeclaration.class)) {
      String name = qualifiedName(declaration);
      // 通常の型はFQN、ローカル型は包含宣言とスコープ内順序を使い、本文に依存させない。
      String typeId = AnalysisIds.hash(relativePath + "|" + name);
      List<JavaAnalysis.Field> fields = new ArrayList<>();
      List<MethodContext> methods = new ArrayList<>();
      if (declaration.isRecordDeclaration()) {
        declaration
            .asRecordDeclaration()
            .getParameters()
            .forEach(
                parameter ->
                    fields.add(
                        new JavaAnalysis.Field(
                            parameter.getNameAsString(),
                            parameter.getTypeAsString(),
                            range(parameter),
                            annotations(parameter.getAnnotations()))));
      }
      extractMembers(declaration, relativePath, name, original, fields, methods);

      String kind =
          declaration.isRecordDeclaration()
              ? "RECORD"
              : declaration.isEnumDeclaration()
                  ? "ENUM"
                  : declaration.isAnnotationDeclaration()
                      ? "ANNOTATION"
                      : declaration.asClassOrInterfaceDeclaration().isInterface()
                          ? "INTERFACE"
                          : "CLASS";
      var type =
          new JavaAnalysis.Type(
              typeId,
              name,
              kind,
              range(declaration),
              annotations(declaration.getAnnotations()),
              SpringRoles.classify(declaration),
              List.copyOf(fields),
              methods.stream().map(MethodContext::info).toList());
      contexts.add(new TypeContext(type, declaration, unit, relativePath, List.copyOf(methods)));
    }
    return contexts;
  }

  /** フィールドと明示的なconstructor・methodを抽出し、原文の本文を保持する。 */
  private void extractMembers(
      TypeDeclaration<?> declaration,
      String relativePath,
      String name,
      SourceText original,
      List<JavaAnalysis.Field> fields,
      List<MethodContext> methods) {
    for (var member : declaration.getMembers()) {
      if (member.isFieldDeclaration()) {
        for (var variable : member.asFieldDeclaration().getVariables()) {
          fields.add(
              new JavaAnalysis.Field(
                  variable.getNameAsString(),
                  variable.getTypeAsString(),
                  range(member),
                  annotations(member.asFieldDeclaration().getAnnotations())));
        }
      }

      if (member instanceof CompactConstructorDeclaration compact) {
        methods.add(compactConstructor(declaration, compact, relativePath, name, original));
      } else if (member instanceof CallableDeclaration<?> callable) {
        methods.add(callableMethod(callable, relativePath, name, original));
      }
    }
  }

  /** compact constructorの暗黙の引数をrecord componentから取得する。 */
  private MethodContext compactConstructor(
      TypeDeclaration<?> declaration,
      CompactConstructorDeclaration compact,
      String relativePath,
      String name,
      SourceText original) {
    var parameters = declaration.asRecordDeclaration().getParameters();
    String signature =
        compact.getNameAsString()
            + "("
            + parameters.stream()
                .map(Parameter::getTypeAsString)
                .collect(java.util.stream.Collectors.joining(","))
            + ")";
    var info =
        new JavaAnalysis.Method(
            AnalysisIds.hash(relativePath + "|" + name + "|" + signature),
            name,
            compact.getNameAsString(),
            signature,
            true,
            range(compact),
            original.slice(compact.getBody()),
            annotations(compact.getAnnotations()),
            parameters.stream().map(Parameter::getTypeAsString).distinct().sorted().toList());

    return new MethodContext(info, compact, List.copyOf(parameters));
  }

  /** 本文を原文から切り出し、引数型を含む署名でオーバーロードを区別する。 */
  private MethodContext callableMethod(
      CallableDeclaration<?> callable, String relativePath, String name, SourceText original) {
    String signature =
        callable.getNameAsString()
            + "("
            + callable.getParameters().stream()
                .map(
                    parameter -> parameter.getTypeAsString() + (parameter.isVarArgs() ? "..." : ""))
                .collect(java.util.stream.Collectors.joining(","))
            + ")";
    String methodId = AnalysisIds.hash(relativePath + "|" + name + "|" + signature);
    String body = "";
    if (callable instanceof MethodDeclaration method) {
      body = method.getBody().map(original::slice).orElse("");
    } else if (callable instanceof ConstructorDeclaration constructor) {
      body = original.slice(constructor.getBody());
    }

    var info =
        new JavaAnalysis.Method(
            methodId,
            name,
            callable.getNameAsString(),
            signature,
            callable.isConstructorDeclaration(),
            range(callable),
            body,
            annotations(callable.getAnnotations()),
            callable.findAll(ClassOrInterfaceType.class).stream()
                .map(ClassOrInterfaceType::asString)
                .distinct()
                .sorted()
                .toList());
    return new MethodContext(info, callable, List.copyOf(callable.getParameters()));
  }

  static Range range(Node node) {
    return node.getRange().map(r -> new Range(r.begin.line, r.end.line)).orElse(new Range(1, 1));
  }

  private static List<String> annotations(List<AnnotationExpr> annotations) {
    return annotations.stream().map(AnnotationExpr::getNameAsString).toList();
  }

  private static String qualifiedName(TypeDeclaration<?> declaration) {
    return declaration.getFullyQualifiedName().orElseGet(() -> localName(declaration));
  }

  /** ローカル型はFQNを持たないため、包含宣言と同名宣言の順序で補助識別子を作る。 */
  private static String localName(TypeDeclaration<?> declaration) {
    Node scope = declaration.getParentNode().orElseThrow();
    while (!(scope instanceof CallableDeclaration<?>) && !(scope instanceof TypeDeclaration<?>)) {
      if (scope.getParentNode().isEmpty()) break;
      scope = scope.getParentNode().orElseThrow();
    }

    String enclosing;
    if (scope instanceof CallableDeclaration<?> callable) {
      String owner =
          callable
              .findAncestor(TypeDeclaration.class)
              .map(JavaStructureExtractor::qualifiedName)
              .orElse("");
      enclosing = owner + "#" + callable.getSignature().asString();
    } else if (scope instanceof TypeDeclaration<?> type) {
      enclosing = qualifiedName(type);
    } else {
      enclosing = "local";
    }

    // 行・列だけでは別メソッドや同一行の宣言が衝突する。本文や改行の変更でも維持する。
    var sameName =
        scope.findAll(TypeDeclaration.class).stream()
            .filter(type -> type.getNameAsString().equals(declaration.getNameAsString()))
            .toList();
    int ordinal = 0;
    for (var candidate : sameName) {
      if (candidate == declaration) break;
      ordinal++;
    }
    return enclosing + "." + declaration.getNameAsString() + "@" + ordinal;
  }
}
