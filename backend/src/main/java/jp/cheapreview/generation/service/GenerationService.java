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
import jp.cheapreview.workspace.service.WorkspaceService;
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
  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
  private final Map<String, Entry> jobs = new LinkedHashMap<>();
  private String active;

  private static final class Entry {
    final GenerationDtos.Start input;
    GenerationDtos.Job view;
    Future<?> future;
    String failedRaw;

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
      AnswerValidator answers) {
    this.workspaces = workspaces;
    this.budgets = budgets;
    this.settings = settings;
    this.provider = provider;
    this.answers = answers;
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
            original.revision());
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
    var deadline = deadlines.schedule(() -> timeout(entry), 240, TimeUnit.SECONDS);
    long started = System.nanoTime();
    try {
      synchronized (this) {
        if (entry.view.state() == State.CANCELLED) return;
        transition(entry, State.GENERATING, null, null);
      }
      if (!settings.current(credentials.version())) throw new GenerationFailure("STALE_SETTINGS");
      var reply =
          provider.generate(
              PromptMaterializer.bundle(entry.input.input(), preview.selectedChunks()),
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
              entry.input.input().workspaceId());
      synchronized (this) {
        if (entry.view.state() == State.GENERATING)
          transition(entry, State.SUCCEEDED, null, result);
      }
    } catch (GenerationFailure failure) {
      fail(entry, failure.getMessage());
    } catch (Exception exception) {
      fail(entry, "GENERATION_FAILED");
    } finally {
      deadline.cancel(false);
      synchronized (this) {
        if (entry.view.jobId().equals(active)) active = null;
      }
    }
  }

  private synchronized void timeout(Entry entry) {
    if (entry.view.state() == State.QUEUED || entry.view.state() == State.GENERATING) {
      transition(entry, State.FAILED, "GENERATION_TIMEOUT", null);
      if (entry.future != null) entry.future.cancel(true);
    }
  }

  private synchronized void fail(Entry entry, String code) {
    if (entry.view.state() == State.QUEUED || entry.view.state() == State.GENERATING)
      transition(entry, State.FAILED, code, null);
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
