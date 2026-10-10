package jp.cheapreview.search.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jp.cheapreview.analysis.dto.JavaAnalysis;

/** 保存済み原文のASTを使い、コメントを所属する宣言へ一度だけ関連付ける。 */
final class SearchComments {
  record Text(String file, Map<String, String> methods) {}

  static Text extract(String source, JavaAnalysis.File analysis) {
    var unit =
        new JavaParser(
                new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
            .parse(source)
            .getResult();
    if (unit.isEmpty()) return new Text("", Map.of());

    var owners = declarations(unit.get().findAll(TypeDeclaration.class), analysis);
    List<String> fileComments = new ArrayList<>();
    Map<String, List<String>> methodComments = new LinkedHashMap<>();
    for (var comment : unit.get().getAllComments()) {
      Node owner = owner(comment, owners);
      if (owner == null) fileComments.add(comment.getContent());
      else
        methodComments
            .computeIfAbsent(owners.get(owner), key -> new ArrayList<>())
            .add(comment.getContent());
    }

    Map<String, String> methods = new LinkedHashMap<>();
    methodComments.forEach((id, comments) -> methods.put(id, String.join(" ", comments)));
    return new Text(String.join(" ", fileComments), Map.copyOf(methods));
  }

  /** 同じ原文・構文解析設定での宣言順を使い、行だけでは区別できない同一行の宣言も対応させる。 */
  private static Map<Node, String> declarations(
      List<TypeDeclaration> types, JavaAnalysis.File analysis) {
    Map<Node, String> owners = new IdentityHashMap<>();
    // 対応が確認できない場合はファイルの特徴語として残し、別メソッドへ推測で割り当てない。
    if (analysis == null || types.size() != analysis.types().size()) return owners;
    for (int i = 0; i < types.size(); i++) {
      TypeDeclaration<?> type = types.get(i);
      var nodes =
          type.getMembers().stream()
              .filter(
                  node ->
                      node instanceof CallableDeclaration<?>
                          || node instanceof CompactConstructorDeclaration)
              .toList();
      var methods = analysis.types().get(i).methods();
      if (nodes.size() != methods.size()) continue;
      for (int j = 0; j < nodes.size(); j++) {
        var node = nodes.get(j);
        var method = methods.get(j);
        if (node.getRange()
            .map(
                range ->
                    range.begin.line == method.range().beginLine()
                        && range.end.line == method.range().endLine())
            .orElse(false)) {
          owners.put(node, method.methodId());
        }
      }
    }
    return owners;
  }

  /** 本文内の所属を列まで含む範囲で優先し、宣言のJavadoc等だけASTの付属関係を使う。 */
  private static Node owner(Comment comment, Map<Node, String> owners) {
    Node containing = null;
    for (var node : owners.keySet()) {
      if (node.getRange().isPresent()
          && comment.getRange().isPresent()
          && node.getRange().get().contains(comment.getRange().get())) {
        // ネストしたローカル型のメソッドでは、最も内側の宣言にだけ所属させる。
        if (containing == null || containing.getRange().get().contains(node.getRange().get()))
          containing = node;
      }
    }
    var attached = comment.getCommentedNode().filter(owners::containsKey).orElse(null);
    // ローカル型のJavadocは外側の本文範囲にも入るため、内側の宣言への付属を優先する。
    if (attached != null
        && (containing == null
            || containing.getRange().get().contains(attached.getRange().orElseThrow())))
      return attached;
    return containing;
  }
}
