package jp.cheapreview.generation.service;

/** 機密を含み得るHTTP本文やcauseを保持しない、表示用の固定エラー。 */
public final class GenerationFailure extends RuntimeException {
  private static final java.util.Set<String> CODES =
      java.util.Set.of(
          "MODEL_UNSUPPORTED",
          "MODEL_NOT_FOUND",
          "GEMINI_INVALID_REQUEST",
          "GEMINI_PRECONDITION_FAILED",
          "GEMINI_QUOTA_EXCEEDED",
          "GEMINI_BILLING_REQUIRED",
          "GEMINI_SERVER_ERROR",
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

  private final jp.cheapreview.generation.dto.GenerationDtos.ErrorDetails details;

  public GenerationFailure(String code) {
    this(code, null);
  }

  public GenerationFailure(
      String code, jp.cheapreview.generation.dto.GenerationDtos.ErrorDetails details) {
    super(CODES.contains(code) ? code : "GENERATION_FAILED");
    this.details = details;
  }

  public jp.cheapreview.generation.dto.GenerationDtos.ErrorDetails details() {
    return details;
  }
}
