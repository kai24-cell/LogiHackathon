package jp.cheapreview.search.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.search.dto.CodeSearch;
import jp.cheapreview.search.dto.CodeSearch.Candidate;
import jp.cheapreview.workspace.service.WorkspaceCapture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CodeSearchService {
  private static final int MAX_QUESTION_POINTS = 10000;
  private final JavaAnalysisService analysis;
  private final SearchScoring scoring;
  private final int maxIndexes;
  private final Map<String, Cached> indexes = new LinkedHashMap<>();

  private record Cached(String snapshotId, SearchIndex index) {}

  public CodeSearchService(
      JavaAnalysisService analysis,
      SearchScoring scoring,
      @Value("${cheapreview.search.max-indexes:1}") int maxIndexes) {
    if (maxIndexes < 1) throw new IllegalArgumentException("Search index limit must be positive");
    this.analysis = analysis;
    this.scoring = scoring;
    this.maxIndexes = maxIndexes;
  }

  /** 入力を固定し、保存済みsnapshotだけを順位付けする。解析対象のディスクを再読込しない。 */
  public synchronized CodeSearch.Result search(
      String workspaceId, WorkspaceCapture capture, CodeSearch.Request request) {
    Set<String> selected = validate(capture, request);
    SearchIndex index = index(workspaceId, capture);
    var questionVector = index.tfIdf.vector(SearchTerms.counts(request.question()));
    var distances = index.graph.distances(selected);
    List<Candidate> candidates =
        index.documents.stream()
            .map(
                document ->
                    candidate(index, document, questionVector, request.mode(), selected, distances))
            .sorted(
                Comparator.comparingDouble((Candidate c) -> c.score().total())
                    .reversed()
                    .thenComparing(
                        Comparator.comparingDouble((Candidate c) -> c.score().dependency())
                            .reversed())
                    .thenComparing(
                        Comparator.comparingDouble((Candidate c) -> c.score().cosine()).reversed())
                    .thenComparing(Candidate::relativePath)
                    .thenComparingInt(Candidate::beginLine))
            .toList();

    var ranked = assignRanks(candidates);
    var warnings = warnings(index, capture, candidates, selected);

    return new CodeSearch.Result(
        capture.snapshot().snapshotId(),
        request.mode(),
        SearchScoring.FORMULA_VERSION,
        scoring.weights(),
        index.vectors.size(),
        List.copyOf(ranked),
        List.copyOf(warnings));
  }

  /** 並べ替え済みの候補へ1始まりの順位を付け、主選択と内訳をそのまま保持する。 */
  private List<Candidate> assignRanks(List<Candidate> candidates) {
    List<Candidate> ranked = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      var c = candidates.get(i);
      ranked.add(
          new Candidate(
              i + 1,
              c.fileId(),
              c.relativePath(),
              c.primarySelected(),
              c.dependencyDistance(),
              c.bestMethodId(),
              c.beginLine(),
              c.score(),
              c.matchingTerms(),
              c.roles(),
              c.reasons()));
    }
    return List.copyOf(ranked);
  }

  /** 一致なし・起点なし・設定本文の未解析を、利用者が誤解しないよう案内する。 */
  private List<String> warnings(
      SearchIndex index,
      WorkspaceCapture capture,
      List<Candidate> candidates,
      Set<String> selected) {
    List<String> warnings = new ArrayList<>(index.warnings);
    if (candidates.stream().noneMatch(c -> c.score().cosine() > 0)) {
      warnings.add("質問とコードの共通語がありません。類似度は0です。依存関係・役割による順位は意味的一致を保証しません。");
    }
    if (selected.isEmpty()) warnings.add("主選択がないため依存探索は行っていません。");
    if (capture.snapshot().files().stream().anyMatch(file -> !file.language().equals("JAVA"))) {
      warnings.add("設定ファイルはファイル名のみで検索します。設定の本文は読み直しません。");
    }

    return List.copyOf(warnings);
  }

  private Set<String> validate(WorkspaceCapture capture, CodeSearch.Request request) {
    if (request == null
        || request.question() == null
        || request.question().isBlank()
        || request.question().codePointCount(0, request.question().length()) > MAX_QUESTION_POINTS
        || request.mode() == null
        || request.selectedFileIds() == null
        || request.selectedFileIds().stream().anyMatch(id -> id == null)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_REQUEST");
    }
    Set<String> selected = Set.copyOf(request.selectedFileIds());
    Set<String> allowed =
        capture.snapshot().files().stream()
            .map(file -> file.fileId())
            .collect(java.util.stream.Collectors.toSet());
    if (!allowed.containsAll(selected)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SELECTED_FILE");
    }

    return selected;
  }

  /** 再走査では索引を交換する。メモリを抑えるため保持数を設定で制限する。 */
  private SearchIndex index(String workspaceId, WorkspaceCapture capture) {
    var existing = indexes.get(workspaceId);
    if (existing != null && existing.snapshotId().equals(capture.snapshot().snapshotId()))
      return existing.index();

    var index = new SearchIndex(capture, analysis.analyze(capture));
    indexes.remove(workspaceId);
    while (indexes.size() >= maxIndexes) indexes.remove(indexes.keySet().iterator().next());
    indexes.put(workspaceId, new Cached(capture.snapshot().snapshotId(), index));
    return index;
  }

  /** ファイル内の最大cosineを採用し、同点なら元の行順で代表メソッドを固定する。 */
  private Candidate candidate(
      SearchIndex index,
      SearchIndex.Document document,
      Map<String, Double> question,
      CodeSearch.Mode mode,
      Set<String> selected,
      Map<String, Integer> distances) {
    var best =
        document.chunks().stream()
            .min(
                Comparator.comparingDouble(
                        (SearchIndex.Chunk chunk) ->
                            TfIdf.cosine(question, index.vectors.get(chunk)))
                    .reversed()
                    .thenComparingInt(SearchIndex.Chunk::beginLine))
            .orElseThrow();
    double cosine = TfIdf.cosine(question, index.vectors.get(best));
    Integer distance = distances.get(document.file().fileId());
    boolean primary = selected.contains(document.file().fileId());
    double role = RoleFit.score(mode, document, primary, distance);
    var score = scoring.score(cosine, DependencyFiles.score(distance), role);
    var matching =
        question.keySet().stream()
            .filter(term -> index.vectors.get(best).containsKey(term))
            .sorted()
            .toList();
    List<String> reasons = new ArrayList<>();
    if (primary) reasons.add("ユーザー主選択（スコアによって解除しません）");
    if (!matching.isEmpty()) reasons.add("共通語: " + String.join("、", matching));
    if (distance != null) reasons.add("主選択からの最短依存距離: " + distance + "hop");
    if (role > 0) reasons.add("検索観点への役割適合: " + role);
    if (document.analysis() != null
        && document.analysis().parseStatus()
            == jp.cheapreview.analysis.dto.JavaAnalysis.ParseStatus.FAILED) {
      reasons.add("Java構文解析失敗：メソッド抽出不可、ファイル候補として保持");
    }
    if (reasons.isEmpty()) reasons.add("語彙・依存・役割の一致なし（スコア0）");

    return new Candidate(
        0,
        document.file().fileId(),
        document.file().relativePath(),
        primary,
        distance,
        best.methodId(),
        best.beginLine(),
        score,
        matching,
        document.roles().stream().sorted().toList(),
        List.copyOf(reasons));
  }
}
