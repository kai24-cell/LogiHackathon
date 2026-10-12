package jp.cheapreview.budget.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.budget.dto.BudgetPreview;
import jp.cheapreview.budget.dto.BudgetPreview.Chunk;
import jp.cheapreview.budget.dto.BudgetPreview.Excluded;
import jp.cheapreview.budget.dto.BudgetPreview.Request;
import jp.cheapreview.budget.dto.BudgetPreview.Result;
import jp.cheapreview.generation.service.OutboundSanitizer;
import jp.cheapreview.search.dto.CodeSearch;
import jp.cheapreview.search.service.CodeSearchService;
import jp.cheapreview.workspace.service.WorkspaceCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BudgetPreviewService {
  private static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final int MAX_QUESTION_POINTS = 4000;
  private final BudgetSettings settings;
  private final TokenEstimator estimator;
  private final JavaAnalysisService analysis;
  private final CodeSearchService search;
  private final Clock clock;
  private final MmrSelector selector;
  private jp.cheapreview.conversation.service.ConversationService conversations;

  @Autowired
  public void setConversations(
      jp.cheapreview.conversation.service.ConversationService conversations) {
    this.conversations = conversations;
  }

  private final String configVersion = UUID.randomUUID().toString();
  private final Map<String, Result> previews = new LinkedHashMap<>();

  public BudgetPreviewService(
      BudgetSettings settings,
      TokenEstimator estimator,
      JavaAnalysisService analysis,
      CodeSearchService search) {
    this(settings, estimator, analysis, search, Clock.systemUTC());
  }

  @Autowired
  public BudgetPreviewService(
      BudgetSettings settings,
      TokenEstimator estimator,
      JavaAnalysisService analysis,
      CodeSearchService search,
      MmrSettings mmr) {
    this(settings, estimator, analysis, search, Clock.systemUTC(), mmr);
  }

  BudgetPreviewService(
      BudgetSettings settings,
      TokenEstimator estimator,
      JavaAnalysisService analysis,
      CodeSearchService search,
      Clock clock) {
    this(settings, estimator, analysis, search, clock, new MmrSettings(0.7));
  }

  public BudgetPreviewService(
      BudgetSettings settings,
      TokenEstimator estimator,
      JavaAnalysisService analysis,
      CodeSearchService search,
      Clock clock,
      MmrSettings mmr) {
    this.settings = settings;
    this.estimator = estimator;
    this.analysis = analysis;
    this.search = search;
    this.clock = clock;
    this.selector = new MmrSelector(mmr);
  }

  private record Selection(
      List<Chunk> chunks,
      List<Excluded> excluded,
      List<String> warnings,
      long mandatoryTokens,
      boolean usable,
      jp.cheapreview.conversation.dto.ConversationDtos.History history) {}

  /** 必須分を保持したまま予算判定し、候補追加のたびに完全promptと安全余裕を再計算する。 */
  public synchronized Result preview(WorkspaceCapture capture, Request request) {
    validate(capture, request);
    OutboundSanitizer.checkQuestion(request.question());
    var parsed = analysis.analyze(capture);
    var ranking =
        search.search(
            request.workspaceId(),
            capture,
            new CodeSearch.Request(
                request.question(), searchSeeds(request, capture), request.mode()));
    var chunks =
        new SourceChunks(
            parsed, search.methodSimilarities(request.workspaceId(), capture, request.question()));
    var selection = select(capture, request, parsed, ranking, chunks);
    String prompt =
        PromptMaterializer.materialize(request, selection.chunks(), selection.history().text());
    var counts = estimator.estimate(prompt);
    // 履歴の寄与は「必須分＋履歴」から必須分を引き、丸めと見出しも一度だけ計数する。
    var mandatory = selection.chunks().stream().filter(Chunk::mandatory).toList();
    long withHistory =
        estimator
            .estimate(
                PromptMaterializer.materialize(request, mandatory, selection.history().text()))
            .tokens();
    long historyTokens = withHistory - selection.mandatoryTokens();
    long required = estimator.required(counts.tokens());
    var warnings = new ArrayList<>(selection.warnings());
    if (conversations != null && request.conversationId() != null) {
      var view = conversations.get(request.conversationId());
      var paths =
          capture.snapshot().files().stream()
              .map(file -> file.relativePath())
              .collect(Collectors.toSet());
      if (jp.cheapreview.conversation.service.ConversationText.referencePaths(view.imported())
          .stream()
          .anyMatch(path -> !paths.contains(path)))
        warnings.add(
            "取込TXTのコード参照の一部が現在のsnapshotにありません。過去の参考情報として扱い、今回の選択コードを根拠にします。未走査パスは読み込みません。");
      if (!view.turns().isEmpty()) {
        if (view.turns().getLast().references().stream()
            .anyMatch(ref -> !paths.contains(ref.relativePath())))
          warnings.add("前回回答の参照先の一部が現在のsnapshotにありません。旧コードを読み直さず今回の主選択を根拠にします。");
      }
    }
    var price = settings.prices().get(request.modelId());
    if (price == null) warnings.add("モデル料金は未設定のため不明です。無料ではありません。context上限も保証しません。");
    else if (price.contextLimit() == null) warnings.add("モデルのcontext上限は未設定であり保証しません。");
    warnings.add("tokens・料金は暫定推定です。実測値・課金額の保証ではありません。思考・cache・翻訳料金は含めていません。");
    warnings.add(
        "履歴採用 "
            + selection.history().included()
            + "往復、省略 "
            + selection.history().omitted()
            + "往復。取込文は過去の参考資料です。");
    boolean canExecute = selection.usable() && request.inputBudgetTokens() >= required;
    if (!canExecute) warnings.add("必須分と安全余裕が収まりません。主選択を減らすか入力予算を増やしてください。自動削除・切断はしません。");

    var result =
        new Result(
            UUID.randomUUID().toString(),
            capture.snapshot().snapshotId(),
            request.revision(),
            digest(request),
            configVersion,
            clock.instant().plus(LIFETIME),
            counts,
            selection.mandatoryTokens(),
            counts.tokens() - withHistory,
            historyTokens,
            selection.history().included(),
            estimator.margin(counts.tokens()),
            required,
            estimator.required(selection.mandatoryTokens()),
            settings.maxOutputTokens(),
            canExecute,
            selection.chunks(),
            selection.excluded(),
            List.copyOf(warnings),
            estimator.cost(request.modelId(), counts.tokens()),
            prompt,
            new BudgetPreview.Formula(
                settings.asciiWeight(),
                settings.japaneseWeight(),
                settings.otherWeight(),
                settings.marginRate(),
                settings.minMargin(),
                "design-v02-provisional-1"),
            selection.history().omitted(),
            selection.history().version(),
            selection.history());
    remember(result);
    return result;
  }

  /** 保存previewを現在の入力・snapshot・設定・期限と照合し、利用直前にもバックエンドで予算を確認する。 */
  public synchronized Result check(String id, WorkspaceCapture capture, Request request) {
    validate(capture, request);
    var result = previews.get(id);
    if (result == null
        || !clock.instant().isBefore(result.expiresAt())
        || !result.requestDigest().equals(digest(request))
        || !result.snapshotId().equals(capture.snapshot().snapshotId())
        || !result.configVersion().equals(configVersion)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_PREVIEW");
    }
    // Webのボタン制御を信用せず、保持した実際の材料から判定する。外部APIは呼ばない。
    if (!result.canExecute()
        || request.inputBudgetTokens()
            < estimator.required(estimator.estimate(result.promptMaterial()).tokens()))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "BUDGET_TOO_SMALL");
    return result;
  }

  /** 前回参照は現在の相対パスへ解決できるものだけ探索の起点へ足し、主選択へ昇格させない。 */
  private List<String> searchSeeds(Request request, WorkspaceCapture capture) {
    if (conversations == null
        || request.conversationId() == null
        || request.conversationId().isBlank()) return request.selectedFileIds();
    var view = conversations.get(request.conversationId());
    if (view.turns().isEmpty()) return request.selectedFileIds();
    var previousPaths =
        view.turns().getLast().references().stream()
            .map(ref -> ref.relativePath())
            .collect(Collectors.toSet());
    var prior =
        capture.snapshot().files().stream()
            .filter(file -> previousPaths.contains(file.relativePath()))
            .map(file -> file.fileId());
    return java.util.stream.Stream.concat(request.selectedFileIds().stream(), prior)
        .distinct()
        .limit(100)
        .toList();
  }

  /** 主選択と任意候補を分離し、候補重複・ゼロ関連度・安全性を判定して選定理由を残す。 */
  private Selection select(
      WorkspaceCapture capture,
      Request request,
      JavaAnalysis.Result parsed,
      CodeSearch.Result ranking,
      SourceChunks builder) {
    Set<String> mandatoryIds = Set.copyOf(request.selectedFileIds());
    Map<String, JavaAnalysis.File> files =
        parsed.files().stream().collect(Collectors.toMap(JavaAnalysis.File::fileId, file -> file));
    List<Chunk> selected = new ArrayList<>();
    List<Excluded> excluded = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    boolean usable = true;
    for (var file :
        capture.snapshot().files().stream()
            .filter(file -> mandatoryIds.contains(file.fileId()))
            .sorted(Comparator.comparing(file -> file.relativePath()))
            .toList()) {
      var javaFile = files.get(file.fileId());
      if (javaFile == null || !capture.javaSources().containsKey(file.relativePath())) {
        usable = false;
        excluded.add(
            new Excluded(
                file.fileId(),
                file.relativePath(),
                "主選択の設定ファイル本文は未保持。秘密情報方針未確定のため送信候補にできません。Javaを選択してください。"));
      } else
        selected.add(
            OutboundSanitizer.sanitize(
                builder.build(
                    javaFile,
                    capture.javaSources().get(file.relativePath()),
                    request,
                    true,
                    settings.maxMethodsPerFile())));
    }
    long mandatoryTokens =
        estimator.estimate(PromptMaterializer.materialize(request, selected)).tokens();
    boolean fits = usable && request.inputBudgetTokens() >= estimator.required(mandatoryTokens);
    final List<Chunk> mandatory = List.copyOf(selected);
    var history =
        conversations == null
            ? jp.cheapreview.conversation.dto.ConversationDtos.History.empty()
            : conversations.select(
                request.conversationId(),
                request.workspaceId(),
                request.inputBudgetTokens(),
                text -> {
                  long total =
                      estimator
                          .estimate(PromptMaterializer.materialize(request, mandatory, text))
                          .tokens();
                  // 履歴ラベルの差分も25%枠に含める。主選択は固定し、任意候補より先に確定する。
                  return total - mandatoryTokens <= request.inputBudgetTokens() / 4
                      && request.inputBudgetTokens() >= estimator.required(total);
                });
    var candidates = optionalCandidates(capture, request, ranking, builder, files, fits, excluded);
    var retainedMethods = new LinkedHashMap<String, List<String>>();
    selected.forEach(chunk -> retainedMethods.put(chunk.fileId(), chunk.methodIds()));
    candidates.forEach(
        candidate ->
            retainedMethods.put(candidate.chunk().fileId(), candidate.chunk().methodIds()));
    var similarity = search.selectionSimilarity(request.workspaceId(), capture, retainedMethods);
    addWithinBudget(request, selected, candidates, excluded, history.text(), similarity);
    return new Selection(
        List.copyOf(selected),
        List.copyOf(excluded),
        List.copyOf(warnings),
        mandatoryTokens,
        usable,
        history);
  }

  /** 重複・設定本文・関連度0を除外し、保持メソッドと安全性を確定する。 */
  private List<MmrSelector.Candidate> optionalCandidates(
      WorkspaceCapture capture,
      Request request,
      CodeSearch.Result ranking,
      SourceChunks builder,
      Map<String, JavaAnalysis.File> files,
      boolean fits,
      List<Excluded> excluded) {
    List<MmrSelector.Candidate> candidates = new ArrayList<>();
    Set<String> seen = new java.util.HashSet<>(request.selectedFileIds());
    for (var candidate : ranking.candidates()) {
      if (!seen.add(candidate.fileId())) continue;
      var file = files.get(candidate.fileId());
      if (file == null || !capture.javaSources().containsKey(candidate.relativePath())) {
        excluded.add(
            new Excluded(
                candidate.fileId(), candidate.relativePath(), "設定ファイルの秘密情報対策が未確定。本文を読み直さず除外"));
      } else if (candidate.score().total() == 0) {
        excluded.add(new Excluded(candidate.fileId(), candidate.relativePath(), "関連度0の任意候補"));
      } else if (!fits) {
        excluded.add(
            new Excluded(candidate.fileId(), candidate.relativePath(), "主選択の必須分が不足しているため任意追加なし"));
      } else {
        Chunk chunk;
        try {
          chunk =
              OutboundSanitizer.sanitize(
                  builder.build(
                      file,
                      capture.javaSources().get(candidate.relativePath()),
                      request,
                      false,
                      settings.maxMethodsPerFile()));
        } catch (ResponseStatusException exception) {
          // 任意候補の疑わしい情報は除外し、主選択は勝手に除外して成功扱いにしない。
          excluded.add(
              new Excluded(
                  candidate.fileId(),
                  candidate.relativePath(),
                  "秘密情報の疑いがあり任意候補を除外。自動検出は完全ではありません。"));
          continue;
        }
        candidates.add(new MmrSelector.Candidate(chunk, candidate.score().total()));
      }
    }
    return candidates;
  }

  /** 安全余裕を全文から再計算し、大きい候補が入らなくても後続候補を試す。 */
  private void addWithinBudget(
      Request request,
      List<Chunk> selected,
      List<MmrSelector.Candidate> candidates,
      List<Excluded> excluded,
      String history,
      jp.cheapreview.search.service.CodeSimilarity similarity) {
    // 必須チャンクはSの初期集合であり、MMRによって解除・分割しない。
    var result =
        selector.select(
            selected,
            candidates,
            similarity::similarity,
            chunks ->
                estimator
                    .estimate(PromptMaterializer.materialize(request, chunks, history))
                    .tokens(),
            estimator,
            request.inputBudgetTokens());
    for (var decision : result.accepted()) {
      var chunk = decision.candidate().chunk();
      var reasons = new ArrayList<>(chunk.reasons());
      reasons.set(0, selector.reason(decision));
      selected.add(
          new Chunk(
              chunk.fileId(),
              chunk.relativePath(),
              chunk.mandatory(),
              chunk.methodIds(),
              chunk.ranges(),
              chunk.omittedRanges(),
              chunk.code(),
              List.copyOf(reasons)));
    }
    for (var candidate : result.rejected()) {
      excluded.add(
          new Excluded(
              candidate.chunk().fileId(),
              candidate.chunk().relativePath(),
              "候補と安全余裕が予算に収まらないため除外。後続の小さい候補の確認は継続"));
    }
  }

  /** 不正な入力と不明な主選択を拒否する。未知モデルは料金不明としてtokens判定を使える。 */
  private void validate(WorkspaceCapture capture, Request request) {
    if (request == null
        || request.workspaceId() == null
        || request.workspaceId().isBlank()
        || request.snapshotId() == null
        || !request.snapshotId().equals(capture.snapshot().snapshotId())
        || request.question() == null
        || request.question().isBlank()
        || request.question().codePointCount(0, request.question().length()) > MAX_QUESTION_POINTS
        || request.mode() == null
        || request.selectedFileIds() == null
        || request.selectedFileIds().isEmpty()
        || request.selectedFileIds().size() > 100
        || request.selectedFileIds().stream().anyMatch(id -> id == null)
        || request.inputBudgetTokens() < 1024
        || request.inputBudgetTokens() > 100000
        || request.modelId() == null
        || request.modelId().isBlank()
        || request.modelId().length() > 200
        || request.revision() < 0) bad("INVALID_PREVIEW_REQUEST");
    if (conversations != null)
      conversations.checkVersion(request.conversationId(), request.conversationVersion());
    Set<String> selected = Set.copyOf(request.selectedFileIds());
    if (!capture.snapshot().files().stream()
        .map(file -> file.fileId())
        .collect(Collectors.toSet())
        .containsAll(selected)) bad("INVALID_SELECTED_FILE");
    if (request.mode() == CodeSearch.Mode.CLASS_EXPLAIN
        && (request.targetFileId() == null || !selected.contains(request.targetFileId())))
      bad("INVALID_TARGET_FILE");
    if (request.targetMethodId() != null) {
      boolean found =
          analysis.analyze(capture).files().stream()
              .filter(
                  file ->
                      selected.contains(file.fileId())
                          && (request.targetFileId() == null
                              || file.fileId().equals(request.targetFileId())))
              .flatMap(file -> file.types().stream())
              .flatMap(type -> type.methods().stream())
              .anyMatch(method -> method.methodId().equals(request.targetMethodId()));
      if (!found) bad("INVALID_TARGET_METHOD");
    }
    var price = settings.prices().get(request.modelId());
    if (price != null
        && price.contextLimit() != null
        && request.inputBudgetTokens() > price.contextLimit() - settings.maxOutputTokens())
      bad("MODEL_CONTEXT_LIMIT");
  }

  private void bad(String code) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
  }

  /** 固定された設定版は起動ごとに更新。previewは5分・件数上限つきのメモリ保持のみ。 */
  private void remember(Result result) {
    previews.values().removeIf(preview -> !clock.instant().isBefore(preview.expiresAt()));
    while (previews.size() >= settings.maxPreviews())
      previews.remove(previews.keySet().iterator().next());
    previews.put(result.previewId(), result);
  }

  private String digest(Request request) {
    // 区切りを長さ付きにして、質問やID内の改行・区切り文字による同一digestを防ぐ。
    var parts =
        new ArrayList<>(
            List.of(
                request.workspaceId(),
                request.snapshotId(),
                request.question(),
                request.mode().name(),
                Long.toString(request.inputBudgetTokens()),
                request.modelId(),
                request.targetFileId() == null ? "" : request.targetFileId(),
                request.targetMethodId() == null ? "" : request.targetMethodId(),
                Long.toString(request.revision()),
                request.conversationId() == null ? "" : request.conversationId(),
                Long.toString(request.conversationVersion())));
    parts.addAll(request.selectedFileIds().stream().distinct().sorted().toList());
    var canonical = new StringBuilder();
    parts.forEach(part -> canonical.append(part.length()).append(':').append(part));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
