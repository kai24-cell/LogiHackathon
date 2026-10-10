package jp.cheapreview.generation.service;

/** 機密を含み得るHTTP本文やcauseを保持しない、表示用の固定エラー。 */
public final class GenerationFailure extends RuntimeException {
  private static final java.util.Set<String> CODES =
      java.util.Set.of(
          "MODEL_UNSUPPORTED",
          "MODEL_LIMIT_EXCEEDED",
          "PROMPT_INVALID",
          "GEMINI_AUTH_FAILED",
          "GEMINI_RATE_LIMITED",
          "GEMINI_UNAVAILABLE",
          "GENERATION_TIMEOUT",
          "CANCELLED",
          "GEMINI_BLOCKED",
          "INVALID_ANSWER",
          "OUTPUT_LIMIT",
          "RESPONSE_TOO_LARGE",
          "STALE_SETTINGS");

  public GenerationFailure(String code) {
    super(CODES.contains(code) ? code : "GENERATION_FAILED");
  }
}
