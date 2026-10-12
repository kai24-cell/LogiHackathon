package jp.cheapreview.translation.dto;

import java.util.List;
import jp.cheapreview.generation.dto.GenerationDtos.Answer;

/** 英語原文とは別に保持する表示用翻訳。警告に外部API本文や認証値を含めない。 */
public final class TranslationDtos {
  private TranslationDtos() {}

  public enum Status {
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED
  }

  public record Warning(String field, String code) {}

  public record Result(
      Answer displayAnswer,
      boolean partialTranslation,
      List<Warning> warnings,
      int attempt,
      long durationMs,
      Status status,
      String errorCode) {}

  /** 設定の有無だけを返し、資格の有効性は通信するまで保証しない。 */
  public record Readiness(boolean configured, String errorCode) {}
}
