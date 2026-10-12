package jp.cheapreview.conversation.dto;

import java.time.Instant;
import java.util.List;
import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.search.dto.CodeSearch.Mode;

public final class ConversationDtos {
  private ConversationDtos() {}

  public enum Type {
    TRANSCRIPT,
    NOTE
  }

  public enum InputKind {
    FILE,
    PASTE
  }

  public record Create(String workspaceId) {}

  public record ImportRequest(String workspaceId, String importId) {}

  public record PreviewRequest(String text, InputKind inputKind) {
    @Override
    public String toString() {
      return "ImportPreviewRequest[REDACTED]";
    }
  }

  public record TextTurn(String question, String answer) {
    @Override
    public String toString() {
      return "TextTurn[REDACTED]";
    }
  }

  public record Imported(Type type, String note, List<TextTurn> turns) {
    @Override
    public String toString() {
      return "ImportedContext[REDACTED]";
    }
  }

  public record ImportPreview(
      String importId,
      Type type,
      int turns,
      int codePoints,
      long estimatedTokens,
      Instant expiresAt,
      List<String> warnings) {}

  public record Turn(
      String jobId,
      String question,
      Mode mode,
      GenerationDtos.Answer answerEnglish,
      List<GenerationDtos.Reference> references,
      String displayText) {
    @Override
    public String toString() {
      return "ConversationTurn[REDACTED]";
    }
  }

  public record View(
      String conversationId,
      String workspaceId,
      long version,
      List<Turn> turns,
      Imported imported) {
    @Override
    public String toString() {
      return "ConversationView[REDACTED]";
    }
  }

  public record History(long version, String text, int included, int omitted, long tokens) {
    public static History empty() {
      return new History(0, "", 0, 0, 0);
    }

    @Override
    public String toString() {
      return "History[REDACTED]";
    }
  }
}
