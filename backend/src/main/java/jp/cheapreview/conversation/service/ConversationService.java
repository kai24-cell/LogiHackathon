package jp.cheapreview.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import jp.cheapreview.budget.service.TokenEstimator;
import jp.cheapreview.conversation.dto.ConversationDtos;
import jp.cheapreview.conversation.dto.ConversationDtos.History;
import jp.cheapreview.conversation.dto.ConversationDtos.Imported;
import jp.cheapreview.conversation.dto.ConversationDtos.TextTurn;
import jp.cheapreview.conversation.dto.ConversationDtos.Turn;
import jp.cheapreview.conversation.dto.ConversationDtos.Type;
import jp.cheapreview.conversation.dto.ConversationDtos.View;
import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.generation.service.GenerationSettings;
import jp.cheapreview.generation.service.OutboundSanitizer;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 会話と取込previewを上限付きメモリで保持し、外部通信中にロックを保持しない。 */
@Service
public class ConversationService {
  private static final int MAX_TURNS = 20;
  private static final int MAX_HISTORY_TURNS = 6;
  private static final int MAX_BYTES = 1_048_576;
  private static final int MAX_POINTS = 100_000;
  private final Map<String, View> conversations = new LinkedHashMap<>();
  private final Map<String, Instant> touched = new LinkedHashMap<>();
  private final Map<String, Pending> imports = new LinkedHashMap<>();
  private final WorkspaceService workspaces;
  private final TokenEstimator estimator;
  private final GenerationSettings settings;
  private final ObjectMapper mapper;
  private final Clock clock = Clock.systemUTC();
  private final int maxConversations;
  private final int maxImports;

  private record Pending(Imported imported, Instant expiresAt) {}

  public ConversationService(
      WorkspaceService workspaces,
      TokenEstimator estimator,
      GenerationSettings settings,
      ObjectMapper mapper,
      @Value("${cheapreview.conversation.max-conversations:128}") int maxConversations,
      @Value("${cheapreview.conversation.max-imports:128}") int maxImports) {
    if (maxConversations < 1 || maxImports < 1)
      throw new IllegalArgumentException("Invalid conversation limits");
    this.workspaces = workspaces;
    this.estimator = estimator;
    this.settings = settings;
    this.mapper = mapper;
    this.maxConversations = maxConversations;
    this.maxImports = maxImports;
  }

  public synchronized View create(String workspaceId) {
    workspaces.getSnapshot(workspaceId);
    prune();
    if (conversations.size() >= maxConversations)
      fail(HttpStatus.TOO_MANY_REQUESTS, "CONVERSATION_LIMIT");
    var view = new View(UUID.randomUUID().toString(), workspaceId, 0, List.of(), null);
    conversations.put(view.conversationId(), view);
    touch(view.conversationId());
    return view;
  }

  public synchronized View get(String id) {
    prune();
    var view = conversations.get(id);
    if (view == null) fail(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND");
    touch(id);
    return view;
  }

  public synchronized void delete(String id) {
    get(id);
    conversations.remove(id);
    touched.remove(id);
  }

  public synchronized ConversationDtos.ImportPreview preview(
      ConversationDtos.PreviewRequest request) {
    if (request == null
        || request.inputKind() == null
        || request.text() == null
        || request.text().isBlank()) fail(HttpStatus.BAD_REQUEST, "INVALID_TRANSCRIPT");
    String text = request.text();
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      fail(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_TOO_LARGE");
    // JSON文字列から受けても不正surrogateを黙ってreplacementへ置換しない。
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index)))
          fail(HttpStatus.BAD_REQUEST, "INVALID_TRANSCRIPT");
      } else if (Character.isLowSurrogate(character))
        fail(HttpStatus.BAD_REQUEST, "INVALID_TRANSCRIPT");
    }
    checkSafe(text);
    var imported = ConversationText.parse(text);
    int points =
        imported.note() == null ? 0 : imported.note().codePointCount(0, imported.note().length());
    for (var turn : imported.turns()) {
      points +=
          turn.question().codePointCount(0, turn.question().length())
              + turn.answer().codePointCount(0, turn.answer().length());
    }
    if (points > MAX_POINTS) fail(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_TOO_LARGE");
    prune();
    if (imports.size() >= maxImports) fail(HttpStatus.TOO_MANY_REQUESTS, "IMPORT_LIMIT");
    String id = UUID.randomUUID().toString();
    Instant expiry = clock.instant().plus(Duration.ofMinutes(5));
    imports.put(id, new Pending(imported, expiry));
    return new ConversationDtos.ImportPreview(
        id,
        imported.type(),
        imported.turns().size(),
        points,
        estimator.estimate(text).tokens(),
        expiry,
        List.of("取込内容は参考資料です。以前のsnapshot・英語JSONは復元しません。今回のフォルダを選択してください。"));
  }

  /** 利用者の明示確認後だけ新会話を作り、既存会話を上書きしない。 */
  public synchronized View importConversation(String workspaceId, String importId) {
    prune();
    var pending = imports.get(importId);
    if (pending == null) fail(HttpStatus.CONFLICT, "STALE_IMPORT");
    var created = create(workspaceId);
    var view = new View(created.conversationId(), workspaceId, 1, List.of(), pending.imported());
    conversations.put(view.conversationId(), view);
    imports.remove(importId);
    return view;
  }

  /** 最新往復を優先し、25%枠と完全promptの安全余裕を同時に満たす材料だけ固定する。 */
  public synchronized History select(
      String id, String workspaceId, long inputBudget, Predicate<String> fits) {
    if (id == null || id.isBlank()) return History.empty();
    var view = get(id);
    if (!view.workspaceId().equals(workspaceId))
      fail(HttpStatus.BAD_REQUEST, "CONVERSATION_WORKSPACE_MISMATCH");
    List<String> parts = new ArrayList<>();
    String note = "";
    if (view.imported() != null) {
      if (view.imported().type() == Type.NOTE)
        note = "USER_IMPORT_NOTE (reference only):\n" + view.imported().note() + "\n";
      for (var turn : view.imported().turns())
        parts.add(
            "USER_IMPORT_TRANSCRIPT (reference only):\nUSER:\n"
                + turn.question()
                + "\nASSISTANT_TEXT:\n"
                + turn.answer()
                + "\n");
    }
    for (var turn : view.turns()) {
      try {
        parts.add(
            "SESSION_TURN mode="
                + turn.mode()
                + "\nUSER:\n"
                + turn.question()
                + "\nASSISTANT_ENGLISH_JSON:\n"
                + mapper.writeValueAsString(turn.answerEnglish())
                + "\n");
      } catch (Exception failure) {
        fail(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
      }
    }
    long cap = inputBudget / 4;
    if (!note.isEmpty() && (!fits.test(note) || estimator.estimate(note).tokens() > cap))
      fail(HttpStatus.UNPROCESSABLE_ENTITY, "HISTORY_TOO_LARGE");
    String selected = note;
    int included = 0;
    for (int index = parts.size() - 1; index >= 0 && included < MAX_HISTORY_TURNS; index--) {
      String trial = note + parts.get(index) + selected.substring(note.length());
      if (estimator.estimate(trial).tokens() > cap || !fits.test(trial)) {
        if (included == 0) fail(HttpStatus.UNPROCESSABLE_ENTITY, "HISTORY_TOO_LARGE");
        break;
      }
      selected = trial;
      included++;
    }
    return new History(
        view.version(),
        selected,
        included,
        parts.size() - included,
        estimator.estimate(selected).tokens());
  }

  public synchronized void checkVersion(String id, long version) {
    if (id == null || id.isBlank()) return;
    if (get(id).version() != version) fail(HttpStatus.CONFLICT, "STALE_CONVERSATION");
  }

  /** 成功状態と同じ親ロック内で一度だけcommitし、clear済みの会話を再作成しない。 */
  public synchronized void commit(
      String id, long version, String jobId, GenerationDtos.Result result) {
    if (id == null || id.isBlank()) return;
    var view = get(id);
    if (view.turns().stream().anyMatch(turn -> turn.jobId().equals(jobId))) return;
    checkVersion(id, version);
    var turns = new ArrayList<>(view.turns());
    turns.add(
        new Turn(
            jobId,
            result.question(),
            result.mode(),
            result.answerEnglish(),
            result.references(),
            ConversationText.answer(result.answerEnglish(), result.references(), true)));
    int importedTurns = view.imported() == null ? 0 : view.imported().turns().size();
    Imported imported = view.imported();
    if (importedTurns + turns.size() > MAX_TURNS && importedTurns > 0) {
      int removed = Math.min(importedTurns, importedTurns + turns.size() - MAX_TURNS);
      imported =
          new Imported(
              imported.type(), imported.note(), imported.turns().subList(removed, importedTurns));
    }
    while (turns.size() > MAX_TURNS) turns.remove(0);
    conversations.put(
        id, new View(id, view.workspaceId(), version + 1, List.copyOf(turns), imported));
    touch(id);
  }

  /** 再翻訳は表示本文だけを置き換え、英語履歴・往復数を変更しない。 */
  public synchronized void updateDisplay(
      String id, String jobId, GenerationDtos.Answer display, boolean hasUntranslated) {
    if (id == null) return;
    prune();
    var view = conversations.get(id);
    if (view == null) return;
    // 未commit・取消・保持期限で破棄したjobの遅延eventは会話版も変更しない。
    if (view.turns().stream().noneMatch(turn -> turn.jobId().equals(jobId))) return;
    touch(id);
    var turns =
        view.turns().stream()
            .map(
                turn ->
                    turn.jobId().equals(jobId)
                        ? new Turn(
                            turn.jobId(),
                            turn.question(),
                            turn.mode(),
                            turn.answerEnglish(),
                            turn.references(),
                            ConversationText.answer(display, turn.references(), hasUntranslated))
                        : turn)
            .toList();
    conversations.put(
        id, new View(id, view.workspaceId(), view.version() + 1, turns, view.imported()));
  }

  /** 翻訳eventは確定済み往復へ一方向に届け、生成Serviceのロックを取り直さない。 */
  @org.springframework.context.event.EventListener
  public synchronized void translated(
      jp.cheapreview.generation.event.TranslationDisplayUpdated update) {
    prune();
    String id =
        conversations.values().stream()
            .filter(
                view -> view.turns().stream().anyMatch(turn -> turn.jobId().equals(update.jobId())))
            .map(View::conversationId)
            .findFirst()
            .orElse(null);
    if (id != null) updateDisplay(id, update.jobId(), update.display(), update.hasUntranslated());
  }

  public synchronized String export(String id) {
    var view = get(id);
    String text =
        ConversationText.export(
            view.imported(),
            view.turns().stream()
                .map(turn -> new TextTurn(turn.question(), turn.displayText()))
                .toList(),
            clock.instant());
    checkSafe(text);
    // ユーザー本文に明示記入された場合も、絶対フォルダパスを黙って配布しない。
    // ドライブ指定の直前を検証し、https:// の末尾 s:/ を誤検出しない。
    if (java.util.regex.Pattern.compile("(?i)(?:(?<![a-z0-9])[a-z]:[\\\\/]|\\\\\\\\[^\\s]+[\\\\/])")
        .matcher(text)
        .find()) fail(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_REQUIRES_REVIEW");
    return text;
  }

  private void checkSafe(String text) {
    OutboundSanitizer.checkQuestion(text);
    var status = settings.status();
    if (status.configured()) {
      var credentials = settings.capture(status.version(), status.modelId());
      if (text.contains(credentials.key()))
        fail(HttpStatus.UNPROCESSABLE_ENTITY, "SECRET_REQUIRES_REVIEW");
    }
  }

  private void touch(String id) {
    touched.put(id, clock.instant());
  }

  private void prune() {
    imports.values().removeIf(pending -> !clock.instant().isBefore(pending.expiresAt()));
    var expired =
        touched.entrySet().stream()
            .filter(entry -> entry.getValue().isBefore(clock.instant().minus(Duration.ofHours(2))))
            .map(Map.Entry::getKey)
            .toList();
    expired.forEach(
        id -> {
          conversations.remove(id);
          touched.remove(id);
        });
  }

  private static void fail(HttpStatus status, String code) {
    throw new ResponseStatusException(status, code);
  }
}
