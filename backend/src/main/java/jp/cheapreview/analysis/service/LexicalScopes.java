package jp.cheapreview.analysis.service;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Optional;

/** 名前の参照位置で有効な宣言を探し、別スコープの同名変数への置換を防ぐ。 */
final class LexicalScopes {
  private LexicalScopes() {}

  /** 内側の宣言を優先する。型が不明な宣言も返し、外側のフィールドへ落とさない。 */
  static Optional<Type> declaredType(NameExpr use) {
    String name = use.getNameAsString();
    Node scope = use;

    while (scope.getParentNode().isPresent()) {
      scope = scope.getParentNode().orElseThrow();
      Optional<Type> declaration = inScope(scope, use, name);
      if (declaration.isPresent()) {
        return declaration;
      }
    }

    return Optional.empty();
  }

  /** 宣言の有効範囲はブロックだけでは決まらないため、構文ごとに境界を確認する。 */
  private static Optional<Type> inScope(Node scope, NameExpr use, String name) {
    if (scope instanceof LambdaExpr lambda) {
      return parameter(lambda.getParameters(), name);
    }
    if (scope instanceof CatchClause clause) {
      return parameter(List.of(clause.getParameter()), name);
    }
    if (scope instanceof CallableDeclaration<?> callable) {
      return parameter(callable.getParameters(), name);
    }
    if (scope instanceof ForStmt loop) {
      // 初期化子の変数はfor文の内部だけで有効。終了後の参照には到達しない。
      return loop.getInitialization().stream()
          .filter(expression -> expression.isVariableDeclarationExpr())
          .map(expression -> variable(expression.asVariableDeclarationExpr(), use, name))
          .flatMap(Optional::stream)
          .findFirst();
    }
    if (scope instanceof ForEachStmt loop && loop.getBody().isAncestorOf(use)) {
      return variable(loop.getVariable(), use, name);
    }
    if (scope instanceof TryStmt statement) {
      // resourceはtry本体と後続resourceだけで有効。catch/finallyへ持ち出さない。
      if (!statement.getTryBlock().isAncestorOf(use)
          && statement.getResources().stream().noneMatch(resource -> resource.isAncestorOf(use))) {
        return Optional.empty();
      }
      return statement.getResources().stream()
          .filter(expression -> expression.isVariableDeclarationExpr())
          .map(expression -> variable(expression.asVariableDeclarationExpr(), use, name))
          .flatMap(Optional::stream)
          .findFirst();
    }
    if (scope instanceof BlockStmt block) {
      // 直下の宣言だけを見る。内部ブロックや終了済みのループの宣言を拾わない。
      return block.getStatements().stream()
          .filter(statement -> statement.isExpressionStmt())
          .map(statement -> statement.asExpressionStmt().getExpression())
          .filter(expression -> expression.isVariableDeclarationExpr())
          .map(expression -> variable(expression.asVariableDeclarationExpr(), use, name))
          .flatMap(Optional::stream)
          .findFirst();
    }
    if (scope instanceof TypeDeclaration<?> type) {
      return type.getFields().stream()
          .flatMap(field -> field.getVariables().stream())
          .filter(field -> field.getNameAsString().equals(name))
          .map(VariableDeclarator::getType)
          .findFirst();
    }

    return Optional.empty();
  }

  private static Optional<Type> parameter(List<Parameter> parameters, String name) {
    return parameters.stream()
        .filter(parameter -> parameter.getNameAsString().equals(name))
        .map(Parameter::getType)
        .findFirst();
  }

  private static Optional<Type> variable(
      VariableDeclarationExpr declaration, Node use, String name) {
    return declaration.getVariables().stream()
        .filter(variable -> variable.getNameAsString().equals(name))
        .filter(variable -> before(variable, use))
        .map(VariableDeclarator::getType)
        .findFirst();
  }

  private static boolean before(Node declaration, Node use) {
    return declaration.getBegin().isPresent()
        && use.getBegin().isPresent()
        && declaration.getBegin().orElseThrow().compareTo(use.getBegin().orElseThrow()) <= 0;
  }
}
