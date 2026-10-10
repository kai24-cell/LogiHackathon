package jp.cheapreview.search.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.workspace.dto.WorkspaceDtos.SourceFile;
import jp.cheapreview.workspace.service.WorkspaceCapture;

/** 保存済み原文・解析結果からだけ作る、質問と主選択に依存しない索引。 */
final class SearchIndex {
  record Chunk(String fileId, String methodId, int beginLine, Map<String, Integer> terms) {}

  record Document(
      SourceFile file,
      JavaAnalysis.File analysis,
      List<Chunk> chunks,
      Set<String> roles,
      boolean userRelated) {}

  final List<Document> documents;
  final TfIdf tfIdf;
  final Map<Chunk, Map<String, Double>> vectors;
  final DependencyFiles graph;
  final List<String> warnings;

  SearchIndex(WorkspaceCapture capture, JavaAnalysis.Result analysis) {
    Map<String, JavaAnalysis.File> parsed =
        analysis.files().stream()
            .collect(Collectors.toMap(JavaAnalysis.File::fileId, file -> file));
    List<Document> documents = new ArrayList<>();
    for (var file : capture.snapshot().files()) {
      var javaFile = parsed.get(file.fileId());
      String source = capture.javaSources().getOrDefault(file.relativePath(), "");
      documents.add(document(file, javaFile, source));
    }

    this.documents = List.copyOf(documents);
    var chunks = documents.stream().flatMap(document -> document.chunks().stream()).toList();
    tfIdf = new TfIdf(chunks.stream().map(Chunk::terms).toList());
    vectors =
        chunks.stream()
            .collect(
                Collectors.toUnmodifiableMap(chunk -> chunk, chunk -> tfIdf.vector(chunk.terms())));
    graph = new DependencyFiles(analysis);
    warnings = analysis.warnings();
  }

  /** 全メソッドを仮チャンクにする。本文の選別・送信・予算判定はこの索引では行わない。 */
  private Document document(SourceFile file, JavaAnalysis.File analysis, String source) {
    String metadata = metadata(file, analysis);
    List<Chunk> chunks = new ArrayList<>();
    var comments = SearchComments.extract(source, analysis);
    metadata += " " + comments.file();

    if (analysis != null) {
      for (var type : analysis.types()) {
        for (var method : type.methods()) {
          // ASTで所属を確認したコメントだけを採用し、隣接する別メソッドへ混入させない。
          String methodComments = comments.methods().getOrDefault(method.methodId(), "");
          String text =
              metadata
                  + " "
                  + method.signature()
                  + " "
                  + String.join(" ", method.annotations())
                  + " "
                  + String.join(" ", method.referencedTypes())
                  + " "
                  + methodComments;
          chunks.add(
              new Chunk(
                  file.fileId(),
                  method.methodId(),
                  method.range().beginLine(),
                  SearchTerms.counts(text)));
        }
      }
    }

    if (chunks.isEmpty()) {
      // 構文エラーやメソッドなしもファイル候補として残す。設定ファイルは原文を持たず名前だけ。
      chunks.add(new Chunk(file.fileId(), null, 1, SearchTerms.counts(metadata)));
    }
    Set<String> roles =
        analysis == null
            ? Set.of()
            : analysis.types().stream()
                .flatMap(type -> type.roles().stream())
                .map(JavaAnalysis.Role::name)
                .collect(Collectors.toUnmodifiableSet());
    var names = SearchTerms.counts(metadata);
    boolean userRelated = names.containsKey("user") || names.containsKey("account");

    return new Document(file, analysis, List.copyOf(chunks), roles, userRelated);
  }

  private String metadata(SourceFile file, JavaAnalysis.File analysis) {
    if (analysis == null) return file.relativePath();
    String types =
        analysis.types().stream()
            .map(
                type ->
                    type.qualifiedName()
                        + " "
                        + String.join(" ", type.annotations())
                        + " "
                        + type.fields().stream()
                            .map(
                                field ->
                                    field.name()
                                        + " "
                                        + field.type()
                                        + " "
                                        + String.join(" ", field.annotations()))
                            .collect(Collectors.joining(" ")))
            .collect(Collectors.joining(" "));
    return file.relativePath() + " " + analysis.packageName() + " " + types;
  }
}
