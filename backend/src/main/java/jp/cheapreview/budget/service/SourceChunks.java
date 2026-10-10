package jp.cheapreview.budget.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.budget.dto.BudgetPreview.Chunk;
import jp.cheapreview.budget.dto.BudgetPreview.Range;
import jp.cheapreview.budget.dto.BudgetPreview.Request;
import jp.cheapreview.search.dto.CodeSearch.Mode;

/** 保存済みソースの完全な宣言区間だけを連結し、長い本文を文字切りしない。 */
final class SourceChunks {
  private final JavaAnalysis.Result analysis;
  private final Map<String, Double> similarities;

  SourceChunks(JavaAnalysis.Result analysis, Map<String, Double> similarities) {
    this.analysis = analysis;
    this.similarities = similarities;
  }

  /** 主選択は必須規則、関連候補は上位メソッド数で組み立てる。ファイルは再読込しない。 */
  Chunk build(
      JavaAnalysis.File file, String source, Request request, boolean mandatory, int limit) {
    var lines = new Lines(source);
    var parsed =
        new JavaParser(
                new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
            .parse(source);
    if (!parsed.isSuccessful() || file.parseStatus() == JavaAnalysis.ParseStatus.FAILED) {
      return new Chunk(
          file.fileId(),
          file.relativePath(),
          mandatory,
          List.of(),
          List.of(new Range(1, lines.count())),
          List.of(),
          source,
          List.of("構文解析できないため保存済み原文の全文を保持（自動切断なし）"));
    }

    var unit = parsed.getResult().orElseThrow();
    var layout = declarations(unit, file);
    var methods = layout.methods();
    var intervals = layout.intervals();
    var chosen = chooseMethods(file, methods, request, mandatory, limit);
    for (var id : chosen) add(intervals, methods.get(id));
    // 型の閉じ括弧や別宣言と同じ行の場合も、途中の本文だけを送らずメソッド全体へ拡張する。
    var merged = preserveWholeMethods(intervals, methods);
    var code = render(merged, lines);
    Set<String> retained = new HashSet<>();
    methods.forEach(
        (id, node) -> {
          var r = range(node);
          if (merged.stream()
              .anyMatch(part -> part.beginLine() <= r.beginLine() && part.endLine() >= r.endLine()))
            retained.add(id);
        });
    if (request.mode() == Mode.CLASS_EXPLAIN || request.mode() == Mode.PROJECT_STRUCTURE)
      appendSignatures(code, methods, retained, lines);
    return new Chunk(
        file.fileId(),
        file.relativePath(),
        mandatory,
        retained.stream().sorted().toList(),
        merged,
        complement(merged, lines.count()),
        code.toString(),
        List.of(
            mandatory ? "主選択の必須宣言・メソッドを保持" : "関連度／推定量の順で予算に収まる候補を追加",
            "package/import・型・フィールド・constructorを保持。省略は完全な区間単位"));
  }

  private record Layout(Map<String, Node> methods, List<Range> intervals) {}

  /** 既存解析と同じAST宣言順から位置を対応させ、必要宣言の原文区間を収集する。 */
  private Layout declarations(CompilationUnit unit, JavaAnalysis.File file) {
    Map<String, Node> methods = new LinkedHashMap<>();
    List<Range> intervals = new ArrayList<>();
    unit.getPackageDeclaration().ifPresent(node -> add(intervals, node));
    unit.getImports().forEach(node -> add(intervals, node));
    var types = unit.findAll(TypeDeclaration.class);
    for (int i = 0; i < types.size(); i++) {
      TypeDeclaration<?> type = types.get(i);
      type.getComment().ifPresent(comment -> add(intervals, comment));
      var info = file.types().get(i);
      var members = type.getMembers();
      int start = type.getBegin().orElseThrow().line;
      int end = type.getEnd().orElseThrow().line;
      int first = members.isEmpty() ? end : members.get(0).getBegin().orElseThrow().line;
      intervals.add(new Range(start, Math.max(start, first - 1)));
      intervals.add(new Range(end, end));

      int methodIndex = 0;
      for (var member : members) {
        if (member instanceof CallableDeclaration<?>
            || member instanceof CompactConstructorDeclaration) {
          methods.put(info.methods().get(methodIndex++).methodId(), member);
          if (member instanceof ConstructorDeclaration
              || member instanceof CompactConstructorDeclaration) add(intervals, member);
        } else if (!(member instanceof TypeDeclaration<?>)) add(intervals, member);
      }
    }

    return new Layout(methods, intervals);
  }

  /** 行単位の保持が本文と交差する場合、本文全体を採用して途中切断を防ぐ。 */
  private List<Range> preserveWholeMethods(List<Range> intervals, Map<String, Node> methods) {
    var merged = merge(intervals);
    while (true) {
      var current = merged;
      var expanded = new ArrayList<>(merged);
      for (var node : methods.values()) {
        var r = range(node);
        if (current.stream()
            .anyMatch(part -> part.beginLine() <= r.endLine() && part.endLine() >= r.beginLine()))
          add(expanded, node);
      }
      var next = merge(expanded);
      if (next.equals(merged)) return next;
      merged = next;
    }
  }

  /** 元の行番号と省略区間を付けて原文を連結する。 */
  private StringBuilder render(List<Range> merged, Lines lines) {
    var code = new StringBuilder();
    int previous = 0;
    for (var range : merged) {
      if (range.beginLine() > previous + 1)
        code.append("// omitted lines ")
            .append(previous + 1)
            .append("-")
            .append(range.beginLine() - 1)
            .append("\n");
      code.append("// original lines ")
          .append(range.beginLine())
          .append("-")
          .append(range.endLine())
          .append("\n")
          .append(lines.slice(range));
      if (code.charAt(code.length() - 1) != '\n') code.append("\n");
      previous = range.endLine();
    }

    return code;
  }

  /** 上位cosineまたはpublic順を起点に、同じ型の呼出先を最大2段だけ補完する。 */
  private Set<String> chooseMethods(
      JavaAnalysis.File file,
      Map<String, Node> nodes,
      Request request,
      boolean mandatory,
      int limit) {
    var all = file.types().stream().flatMap(type -> type.methods().stream()).toList();
    boolean security =
        mandatory
            && request.mode() == Mode.AUTH_ANALYSIS
            && file.types().stream()
                .flatMap(type -> type.roles().stream())
                .anyMatch(role -> role.name().equals("Security"));
    Set<String> selected = new HashSet<>();
    all.stream()
        .filter(JavaAnalysis.Method::constructor)
        .forEach(method -> selected.add(method.methodId()));
    if (security) all.forEach(method -> selected.add(method.methodId()));

    var candidates = all.stream().filter(method -> !method.constructor()).toList();
    boolean match =
        candidates.stream()
            .anyMatch(method -> similarities.getOrDefault(method.methodId(), 0.0) > 0);
    candidates.stream()
        .filter(method -> match || publicMethod(nodes.get(method.methodId())))
        .sorted(
            Comparator.comparingDouble(
                    (JavaAnalysis.Method method) ->
                        similarities.getOrDefault(method.methodId(), 0.0))
                .reversed()
                .thenComparingInt(method -> method.range().beginLine())
                .thenComparingInt(
                    method -> nodes.get(method.methodId()).getBegin().orElseThrow().column)
                .thenComparing(JavaAnalysis.Method::methodId))
        .limit(limit)
        .forEach(method -> selected.add(method.methodId()));
    if (mandatory
        && request.targetMethodId() != null
        && nodes.containsKey(request.targetMethodId())) selected.add(request.targetMethodId());

    Map<String, JavaAnalysis.Method> byId = new HashMap<>();
    all.forEach(method -> byId.put(method.methodId(), method));
    Set<String> frontier = Set.copyOf(selected);
    for (int depth = 0; depth < 2; depth++) {
      Set<String> next = new HashSet<>();
      for (var edge : analysis.edges()) {
        if (edge.kind() != JavaAnalysis.EdgeKind.METHOD_CALL || !frontier.contains(edge.fromId()))
          continue;
        var from = byId.get(edge.fromId());
        var to = byId.get(edge.toId());
        if (from != null
            && to != null
            && from.declaringType().equals(to.declaringType())
            && !selected.contains(to.methodId())) next.add(to.methodId());
      }
      selected.addAll(next);
      frontier = next;
    }
    return selected;
  }

  private boolean publicMethod(Node node) {
    return node instanceof MethodDeclaration method
        && (method.isPublic()
            || method
                .findAncestor(TypeDeclaration.class)
                .map(
                    type ->
                        type.isClassOrInterfaceDeclaration()
                            && type.asClassOrInterfaceDeclaration().isInterface()
                            && !method.isPrivate())
                .orElse(false));
  }

  /** 本文未選択の署名は原文から切り出す。AST整形や擬似本文を送信コードに混ぜない。 */
  private void appendSignatures(
      StringBuilder code, Map<String, Node> nodes, Set<String> retained, Lines lines) {
    nodes.forEach(
        (id, node) -> {
          if (retained.contains(id) || !(node instanceof MethodDeclaration method)) return;
          var begin = node.getBegin().orElseThrow();
          var finish =
              method
                  .getBody()
                  .map(body -> body.getBegin().orElseThrow())
                  .orElse(node.getEnd().orElseThrow());
          code.append("// declaration only, methodId=")
              .append(id)
              .append(", line=")
              .append(begin.line)
              .append("\n")
              .append(lines.between(begin.line, begin.column, finish.line, finish.column))
              .append(" /* body omitted */\n");
        });
  }

  private static void add(List<Range> ranges, Node node) {
    ranges.add(range(node));
    node.getComment().ifPresent(comment -> ranges.add(range(comment)));
  }

  private static Range range(Node node) {
    var r = node.getRange().orElseThrow();
    return new Range(r.begin.line, r.end.line);
  }

  /** 行区間の重複・隣接を合併し、同じ原文を二重計上しない。 */
  static List<Range> merge(List<Range> ranges) {
    List<Range> result = new ArrayList<>();
    for (var range :
        ranges.stream()
            .sorted(Comparator.comparingInt(Range::beginLine).thenComparingInt(Range::endLine))
            .toList()) {
      if (!result.isEmpty() && range.beginLine() <= result.getLast().endLine() + 1) {
        var last = result.removeLast();
        result.add(new Range(last.beginLine(), Math.max(last.endLine(), range.endLine())));
      } else result.add(range);
    }
    return List.copyOf(result);
  }

  private List<Range> complement(List<Range> ranges, int count) {
    List<Range> result = new ArrayList<>();
    int next = 1;
    for (var range : ranges) {
      if (range.beginLine() > next) result.add(new Range(next, range.beginLine() - 1));
      next = range.endLine() + 1;
    }
    if (next <= count) result.add(new Range(next, count));
    return List.copyOf(result);
  }

  /** CRLFも保持して原文の行・列位置を扱う。元のコードへ整形や書込を行わない。 */
  private static final class Lines {
    private final String source;
    private final List<Integer> starts = new ArrayList<>(List.of(0));

    Lines(String source) {
      this.source = source;
      var matches = Pattern.compile("\r\n|\r|\n").matcher(source);
      while (matches.find()) if (matches.end() < source.length()) starts.add(matches.end());
    }

    int count() {
      return starts.size();
    }

    String slice(Range range) {
      int end = range.endLine() >= starts.size() ? source.length() : starts.get(range.endLine());
      return source.substring(starts.get(range.beginLine() - 1), end);
    }

    String between(int beginLine, int beginColumn, int endLine, int endColumn) {
      return source.substring(
          starts.get(beginLine - 1) + beginColumn - 1, starts.get(endLine - 1) + endColumn - 1);
    }
  }
}
