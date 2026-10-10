package jp.cheapreview.analysis.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.analysis.service.JavaStructureExtractor.TypeContext;
import jp.cheapreview.workspace.service.WorkspaceCapture;
import org.springframework.stereotype.Service;

@Service
public class JavaAnalysisService {
  /** 走査時に固定した原文だけを解析し、構文エラーがあっても他ファイルを継続する。 */
  public synchronized JavaAnalysis.Result analyze(WorkspaceCapture capture) {
    CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver());
    JavaParser parser =
        new JavaParser(
            new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(solver)));
    Map<String, CompilationUnit> units = new LinkedHashMap<>();
    List<JavaAnalysis.File> files = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<TypeContext> contexts = new ArrayList<>();
    try {
      parseFiles(capture, parser, units, files, warnings, contexts);

      // solverへ渡すのは保存済みASTだけ。対象プロジェクトのビルド・依存取得は行わない。
      SnapshotTypeSolvers.add(solver, parser, capture.root(), units);
      var graph = new JavaDependencyExtractor(contexts).extract();
      return new JavaAnalysis.Result(
          capture.snapshot().snapshotId(),
          List.copyOf(files),
          graph.edges(),
          graph.unresolved(),
          List.copyOf(warnings));
    } finally {
      JavaParserFacade.clearInstances();
    }
  }

  /** ファイル単位の失敗を結果に残し、成功したASTだけを依存解析へ渡す。 */
  private void parseFiles(
      WorkspaceCapture capture,
      JavaParser parser,
      Map<String, CompilationUnit> units,
      List<JavaAnalysis.File> files,
      List<String> warnings,
      List<TypeContext> contexts) {
    var extractor = new JavaStructureExtractor();

    for (var file : capture.snapshot().files()) {
      if (!file.language().equals("JAVA")) continue;
      String source = capture.javaSources().get(file.relativePath());
      var parsed = parser.parse(source);

      if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
        String warning = "PARSE_FAILED: " + file.relativePath();
        warnings.add(warning);
        files.add(
            new JavaAnalysis.File(
                file.fileId(),
                file.relativePath(),
                JavaAnalysis.ParseStatus.FAILED,
                "",
                List.of(),
                List.of(),
                List.of(warning)));
        continue;
      }

      CompilationUnit unit = parsed.getResult().orElseThrow();
      units.put(file.relativePath(), unit);
      List<TypeContext> types = extractor.extract(file.relativePath(), source, unit);
      contexts.addAll(types);
      files.add(
          new JavaAnalysis.File(
              file.fileId(),
              file.relativePath(),
              JavaAnalysis.ParseStatus.PARSED,
              unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""),
              unit.getImports().stream()
                  .map(
                      i ->
                          (i.isStatic() ? "static " : "")
                              + i.getNameAsString()
                              + (i.isAsterisk() ? ".*" : ""))
                  .toList(),
              types.stream().map(TypeContext::info).toList(),
              List.of()));
    }
  }
}
