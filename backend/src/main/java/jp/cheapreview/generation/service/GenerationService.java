package jp.cheapreview.generation.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jp.cheapreview.budget.dto.BudgetPreview;
import jp.cheapreview.budget.service.BudgetPreviewService;
import jp.cheapreview.budget.service.PromptMaterializer;
import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.generation.dto.GenerationDtos.State;
import jp.cheapreview.generation.event.TranslationDisplayUpdated;
import jp.cheapreview.translation.dto.TranslationDtos;
import jp.cheapreview.translation.service.TranslationService;
import jp.cheapreview.translation.service.TranslationSymbols;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 開始時の入力を固定し、単一生成・requestId再送・キャンセルを原子的に管理する。 */
@Service
public class GenerationService {
  private static final Duration RETENTION = Duration.ofHours(2);
  private static final int MAX_JOBS = 100;
  private final WorkspaceService workspaces;
  private final BudgetPreviewService budgets;
  private final GenerationSettings settings;
  private final GeminiClient provider;
  private final AnswerValidator answers;
  private final TranslationService translations;
  private final ApplicationEventPublisher events;
  private jp.cheapreview.conversation.service.ConversationService conversations;

  @org.springframework.beans.factory.annotation.Autowired
  public void setConversations(
      jp.cheapreview.conversation.service.ConversationService conversations) {
    this.conversations = conversations;
  }

  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
  private final Map<String, Entry> jobs = new LinkedHashMap<>();
  private String active;

  private static final class Entry {
    final GenerationDtos.Start input;
    GenerationDtos.Job view;
    Future<?> future;
    String failedRaw;
    java.util.List<String> translationSymbols = java.util.List.of();
    boolean translationCancelled;
    boolean translationRunning;

    Entry(GenerationDtos.Start input) {
      this.input = input;
      view =
          new GenerationDtos.Job(
              UUID.randomUUID().toString(), State.QUEUED, null, null, Instant.now());
    }
  }

  public GenerationService(
      WorkspaceService workspaces,
      BudgetPreviewService budgets,
      GenerationSettings settings,
      GeminiClient provider,
      AnswerValidator answers,
      TranslationService translations,
      ApplicationEventPublisher events) {
    this.workspaces = workspaces;
    this.budgets = budgets;
    this.settings = settings;
    this.provider = provider;
    this.answers = answers;
    this.translations = translations;
    this.events = events;
  }

  /** 同じrequestIdには同じjobを返し、異なる入力の再利用や同時生成を拒否する。 */
  public synchronized GenerationDtos.Started start(GenerationDtos.Start start) {
    if (start == null
        || start.input() == null
        || start.previewId() == null
        || start.settingsVersion() == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    try {
      UUID.fromString(start.requestId());
    } catch (Exception exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }
    var original = start.input();
    if (original.selectedFileIds() == null
        || original.selectedFileIds().stream().anyMatch(java.util.Objects::isNull))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    var fixed =
        new BudgetPreview.Request(
            original.workspaceId(),
            original.snapshotId(),
            original.question(),
            original.mode(),
            java.util.List.copyOf(original.selectedFileIds()),
            original.inputBudgetTokens(),
            original.modelId(),
            original.targetFileId(),
            original.targetMethodId(),
            original.revision(),
            original.conversationId(),
            original.conversationVersion());
    var request =
        new GenerationDtos.Start(
            start.previewId(), start.requestId(), start.settingsVersion(), fixed);
    prune();
    var previous = jobs.get(request.requestId());
    if (previous != null) {
      if (!previous.input.equals(request))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
      return new GenerationDtos.Started(previous.view.jobId());
    }
    if (active != null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "BUSY");
    if (jobs.size() >= MAX_JOBS)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "JOB_LIMIT");
    var capture = workspaces.getCapture(fixed.workspaceId(), fixed.snapshotId());
    var preview = budgets.check(request.previewId(), capture, fixed);
    var credentials = settings.capture(request.settingsVersion(), fixed.modelId());
    if (preview.promptMaterial().contains(credentials.key()))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SECRET_REQUIRES_REVIEW");
    workspaces.verifyUnchanged(fixed.workspaceId(), fixed.snapshotId(), preview.selectedChunks());
    var entry = new Entry(request);
    jobs.put(request.requestId(), entry);
    active = entry.view.jobId();
    entry.future = workers.submit(() -> generate(entry, preview, credentials));
    return new GenerationDtos.Started(entry.view.jobId());
  }

  /** ジョブ結果だけを返し、キー・送信全文・不正な生回答は外部へ返さない。 */
  public synchronized GenerationDtos.Job get(String id) {
    prune();
    return find(id).view;
  }

  /** 再走査でWeb部品が再生成されても、実行中ジョブの取得・キャンセルへ復帰できる。 */
  public synchronized GenerationDtos.Started activeJob() {
    return new GenerationDtos.Started(active);
  }

  /** 再翻訳は保存済み英語回答だけを使い、Gemini・再走査・履歴commitを呼ばない。 */
  public synchronized GenerationDtos.Started translate(String id) {
    var entry = find(id);
    if (entry.view.result() == null
        || (entry.view.state() != State.SUCCEEDED
            && entry.view.state() != State.TRANSLATION_FAILED))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSLATION_NOT_AVAILABLE");
    if (active != null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "BUSY");
    int attempt = entry.view.translation() == null ? 1 : entry.view.translation().attempt() + 1;
    entry.translationCancelled = false;
    var previous = entry.view.translation();
    var running =
        new TranslationDtos.Result(
            previous == null ? entry.view.result().answerEnglish() : previous.displayAnswer(),
            previous != null && previous.partialTranslation(),
            previous == null ? java.util.List.of() : previous.warnings(),
            attempt,
            0,
            TranslationDtos.Status.RUNNING,
            null);
    // 完了済みジョブ状態は巻き戻さず、翻訳だけの進行を別のstatusで表す。
    entry.view =
        new GenerationDtos.Job(
            entry.view.jobId(),
            entry.view.state(),
            null,
            entry.view.result(),
            entry.view.createdAt(),
            null,
            running);
    active = id;
    entry.future =
        workers.submit(
            () -> {
              try {
                finishTranslation(entry, attempt);
              } finally {
                synchronized (GenerationService.this) {
                  if (id.equals(active)) active = null;
                }
              }
            });
    return new GenerationDtos.Started(id);
  }

  /** 既存走査ジョブと生成ジョブを同じGET契約へ統合し、旧走査のstate/errorCodeを維持する。 */
  public GenerationDtos.Job query(String id) {
    try {
      return get(id);
    } catch (ResponseStatusException exception) {
      if (exception.getStatusCode() != HttpStatus.NOT_FOUND) throw exception;
      var scan = workspaces.getJob(id);
      return new GenerationDtos.Job(
          scan.jobId(), State.valueOf(scan.state().name()), scan.errorCode(), null, null);
    }
  }

  public synchronized GenerationDtos.Job cancel(String id) {
    var entry = find(id);
    if (entry.view.state() == State.TRANSLATING
        || (entry.view.translation() != null
            && entry.view.translation().status() == TranslationDtos.Status.RUNNING)) {
      entry.translationCancelled = true;
      var previous = entry.view.translation();
      var warnings =
          new java.util.ArrayList<TranslationDtos.Warning>(
              previous == null ? java.util.List.of() : previous.warnings());
      warnings.add(new TranslationDtos.Warning("answer", "TRANSLATION_CANCELLED"));
      var cancelled =
          new TranslationDtos.Result(
              previous == null ? entry.view.result().answerEnglish() : previous.displayAnswer(),
              previous != null && previous.partialTranslation(),
              java.util.List.copyOf(warnings),
              previous == null ? 1 : previous.attempt(),
              previous == null ? 0 : previous.durationMs(),
              TranslationDtos.Status.FAILED,
              "TRANSLATION_CANCELLED");
      // 生成成功は取り消さず、翻訳だけ止める。遅延した翻訳結果は下記flagで採用しない。
      entry.view =
          new GenerationDtos.Job(
              entry.view.jobId(),
              State.TRANSLATION_FAILED,
              null,
              entry.view.result(),
              entry.view.createdAt(),
              null,
              cancelled);
      if (entry.translationRunning && entry.future != null) entry.future.cancel(true);
      events.publishEvent(new TranslationDisplayUpdated(id, cancelled.displayAnswer(), true));
      return entry.view;
    }
    if (entry.view.state() == State.QUEUED || entry.view.state() == State.GENERATING) {
      boolean running = entry.view.state() == State.GENERATING;
      transition(entry, State.CANCELLED, "CANCELLED", null);
      if (running && entry.future != null) entry.future.cancel(true);
      // 物理的な通信停止が確認できるまでactiveを解放しない。キャンセルは課金取消を保証しない。
    }
    return entry.view;
  }

  /** 固定入力・マスク済みpromptを一度だけ生成し、後続翻訳が使う英語DTOを保持する。 */
  private void generate(
      Entry entry, BudgetPreview.Result preview, GenerationSettings.Credentials credentials) {
    var diagnostic = new GenerationDiagnostics(GenerationDtos.ApiStage.JOB);
    var deadline = deadlines.schedule(() -> timeout(entry, diagnostic), 240, TimeUnit.SECONDS);
    long started = System.nanoTime();
    try {
      synchronized (this) {
        if (entry.view.state() == State.CANCELLED) return;
        transition(entry, State.GENERATING, null, null);
      }
      if (!settings.current(credentials.version())) throw new GenerationFailure("STALE_SETTINGS");
      var reply =
          provider.generate(
              PromptMaterializer.bundle(
                  entry.input.input(), preview.selectedChunks(), preview.history().text()),
              credentials,
              preview.minimumBudgetTokens(),
              preview.maximumOutputTokens());
      // stubの異常応答もキーを保持しない。失敗rawはメモリ内部のみ、修復APIは呼ばない。
      String raw = SecretRedaction.redact(reply.rawText(), credentials.key());
      AnswerValidator.Validated validated;
      try {
        validated = answers.validate(raw, preview, entry.input.input(), credentials.key());
      } catch (GenerationFailure failure) {
        synchronized (this) {
          entry.failedRaw = raw;
        }
        throw failure;
      }
      var result =
          new GenerationDtos.Result(
              validated.answer(),
              validated.references(),
              validated.warnings(),
              reply.usage(),
              preview.counts().tokens(),
              Duration.ofNanos(System.nanoTime() - started).toMillis(),
              reply.finishReason(),
              reply.providerRequestId(),
              credentials.modelId(),
              preview.snapshotId(),
              entry.input.input().question(),
              entry.input.input().mode(),
              entry.input.input().workspaceId(),
              credentials.source());
      synchronized (this) {
        if (entry.view.state() != State.GENERATING) return;
        // 英語成功はここで一度だけ確定する。翻訳失敗・翻訳のみ中止でも履歴を失わない。
        // 通常生成cancel/clear済みならこの境界を通らず、翻訳の再試行ではcommitしない。
        if (conversations != null)
          conversations.commit(
              entry.input.input().conversationId(),
              preview.conversationVersion(),
              entry.view.jobId(),
              result);
        // 240秒は生成期限。英語取得後は翻訳の待機で取得済み結果を失わないよう解除する。
        deadline.cancel(false);
        entry.translationSymbols = TranslationSymbols.from(preview.selectedChunks());
        transition(entry, State.TRANSLATING, null, result);
      }
      finishTranslation(entry, 1);
    } catch (GenerationFailure failure) {
      fail(entry, failure.getMessage(), failure.details());
    } catch (ResponseStatusException exception) {
      fail(entry, "STALE_CONVERSATION");
    } catch (Exception exception) {
      fail(entry, "GENERATION_FAILED");
    } finally {
      diagnostic.end();
      deadline.cancel(false);
      synchronized (this) {
        if (entry.view.jobId().equals(active)) active = null;
      }
    }
  }

  /** 翻訳の失敗は生成結果とusageを消さず、英語fallbackと警告を表示する。 */
  private void finishTranslation(Entry entry, int attempt) {
    GenerationDtos.Result result;
    synchronized (this) {
      if (entry.translationCancelled) return;
      entry.translationRunning = true;
      result = entry.view.result();
    }
    TranslationDtos.Result translated;
    try {
      translated =
          translations.translate(result.answerEnglish(), entry.translationSymbols, attempt);
    } catch (Exception ignored) {
      translated =
          new TranslationDtos.Result(
              result.answerEnglish(),
              false,
              java.util.List.of(new TranslationDtos.Warning("answer", "TRANSLATION_FAILED")),
              attempt,
              0,
              TranslationDtos.Status.FAILED,
              "TRANSLATION_FAILED");
    }
    synchronized (this) {
      entry.translationRunning = false;
      if (entry.translationCancelled) return;
      State state =
          translated.status() == TranslationDtos.Status.SUCCEEDED
              ? State.SUCCEEDED
              : State.TRANSLATION_FAILED;
      entry.view =
          new GenerationDtos.Job(
              entry.view.jobId(), state, null, result, entry.view.createdAt(), null, translated);
      events.publishEvent(
          new TranslationDisplayUpdated(
              entry.view.jobId(),
              translated.displayAnswer(),
              translated.partialTranslation()
                  || translated.status() == TranslationDtos.Status.FAILED));
    }
  }

  private synchronized void timeout(Entry entry, GenerationDiagnostics diagnostic) {
    if (entry.view.state() == State.QUEUED || entry.view.state() == State.GENERATING) {
      var failure = diagnostic.timeout(GenerationDtos.TimeoutType.JOB_DEADLINE);
      fail(entry, failure.getMessage(), failure.details());
      if (entry.future != null) entry.future.cancel(true);
    }
  }

  private synchronized void fail(Entry entry, String code) {
    fail(entry, code, null);
  }

  private synchronized void fail(Entry entry, String code, GenerationDtos.ErrorDetails details) {
    if (entry.view.state() == State.QUEUED || entry.view.state() == State.GENERATING)
      entry.view =
          new GenerationDtos.Job(
              entry.view.jobId(), State.FAILED, code, null, entry.view.createdAt(), details);
  }

  private void transition(Entry entry, State state, String code, GenerationDtos.Result result) {
    entry.view =
        new GenerationDtos.Job(entry.view.jobId(), state, code, result, entry.view.createdAt());
  }

  private Entry find(String id) {
    return jobs.values().stream()
        .filter(entry -> entry.view.jobId().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
  }

  private void prune() {
    Instant limit = Instant.now().minus(RETENTION);
    jobs.values()
        .removeIf(
            entry -> !entry.view.jobId().equals(active) && entry.view.createdAt().isBefore(limit));
  }

  @PreDestroy
  public void close() {
    workers.shutdownNow();
    deadlines.shutdownNow();
    jobs.clear();
  }
}
