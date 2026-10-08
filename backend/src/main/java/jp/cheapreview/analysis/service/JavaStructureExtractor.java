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

  List<TypeContext> extract(String relativePath, String source, CompilationUnit unit) {
    List<TypeContext> contexts = new ArrayList<>();
    SourceText original = new SourceText(source);
    for (TypeDeclaration<?> declaration : unit.findAll(TypeDeclaration.class)) {
      String name = declaration.getFullyQualifiedName().orElseGet(() -> localName(declaration));
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
      declaration
          .getMembers()
          .forEach(
              member -> {
                if (member.isFieldDeclaration()) {
                  member
                      .asFieldDeclaration()
                      .getVariables()
                      .forEach(
                          variable ->
                              fields.add(
                                  new JavaAnalysis.Field(
                                      variable.getNameAsString(),
                                      variable.getTypeAsString(),
                                      range(member),
                                      annotations(member.asFieldDeclaration().getAnnotations()))));
                }
                if (member instanceof CompactConstructorDeclaration compact) {
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
                          parameters.stream()
                              .map(Parameter::getTypeAsString)
                              .distinct()
                              .sorted()
                              .toList());
                  methods.add(new MethodContext(info, compact, List.copyOf(parameters)));
                }
                if (member instanceof CallableDeclaration<?> callable) {
                  String signature =
                      callable.getNameAsString()
                          + "("
                          + callable.getParameters().stream()
                              .map(p -> p.getTypeAsString() + (p.isVarArgs() ? "..." : ""))
                              .collect(java.util.stream.Collectors.joining(","))
                          + ")";
                  String methodId = AnalysisIds.hash(relativePath + "|" + name + "|" + signature);
                  String body = "";
                  if (callable instanceof MethodDeclaration method) {
                    body = method.getBody().map(b -> original.slice(b)).orElse("");
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
                  methods.add(
                      new MethodContext(info, callable, List.copyOf(callable.getParameters())));
                }
              });
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

  static Range range(Node node) {
    return node.getRange().map(r -> new Range(r.begin.line, r.end.line)).orElse(new Range(1, 1));
  }

  private static List<String> annotations(List<AnnotationExpr> annotations) {
    return annotations.stream().map(AnnotationExpr::getNameAsString).toList();
  }

  private static String localName(TypeDeclaration<?> declaration) {
    return declaration
            .findAncestor(TypeDeclaration.class)
            .map(parent -> localName(parent) + ".")
            .orElse("")
        + declaration.getNameAsString()
        + "@"
        + range(declaration).beginLine();
  }
}
