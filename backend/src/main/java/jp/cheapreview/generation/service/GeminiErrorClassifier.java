package jp.cheapreview.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import jp.cheapreview.generation.dto.GenerationDtos.ApiStage;
import jp.cheapreview.generation.dto.GenerationDtos.ApiStatus;
import jp.cheapreview.generation.dto.GenerationDtos.ErrorDetails;
import jp.cheapreview.generation.dto.GenerationDtos.LimitType;

/** Googleの定型エラーだけを分類し、生message・metadata・認証値を破棄する。 */
final class GeminiErrorClassifier {
  private GeminiErrorClassifier() {}

  private static final Set<String> KEY_ERRORS =
      Set.of("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_NOT_FOUND", "API_KEY_SERVICE_BLOCKED");

  /** モデル廃止やversion非互換は404だけでは断定できないため、見つからない状態として扱う。 */
  static GenerationFailure classify(int httpStatus, JsonNode body, ApiStage stage) {
    JsonNode error = body.path("error");
    ApiStatus apiStatus = status(error.path("status").asText());
    LimitType limit = limit(error.path("details"));
    String code;
    if (httpStatus == 401 || httpStatus == 403 || keyError(error.path("details"))) {
      code = "GEMINI_AUTH_FAILED";
    } else if (httpStatus == 404) {
      code = "MODEL_NOT_FOUND";
    } else if (httpStatus == 402) {
      code = "GEMINI_BILLING_REQUIRED";
    } else if (httpStatus == 429) {
      code = hasQuota(error.path("details")) ? "GEMINI_QUOTA_EXCEEDED" : "GEMINI_RATE_LIMITED";
    } else if (httpStatus == 400 || httpStatus == 422) {
      code =
          apiStatus == ApiStatus.FAILED_PRECONDITION
              ? "GEMINI_PRECONDITION_FAILED"
              : "GEMINI_INVALID_REQUEST";
    } else if (httpStatus == 408 || httpStatus == 504) {
      code = "GENERATION_TIMEOUT";
    } else if (httpStatus == 500) {
      code = "GEMINI_SERVER_ERROR";
    } else {
      code = "GEMINI_UNAVAILABLE";
    }
    return new GenerationFailure(code, new ErrorDetails(httpStatus, apiStatus, stage, limit));
  }

  private static ApiStatus status(String value) {
    try {
      return ApiStatus.valueOf(value);
    } catch (IllegalArgumentException exception) {
      return ApiStatus.UNKNOWN;
    }
  }

  private static boolean keyError(JsonNode details) {
    if (!details.isArray()) return false;
    for (JsonNode detail : details) {
      if (detail.path("@type").asText().equals("type.googleapis.com/google.rpc.ErrorInfo")
          && KEY_ERRORS.contains(detail.path("reason").asText())) return true;
    }
    return false;
  }

  private static boolean hasQuota(JsonNode details) {
    if (!details.isArray()) return false;
    for (JsonNode detail : details) {
      if (detail.path("@type").asText().equals("type.googleapis.com/google.rpc.QuotaFailure"))
        return true;
    }
    return false;
  }

  /** 割当IDは表示せず、既知の時間単位だけをenumへ写す。未知形式は推測しない。 */
  private static LimitType limit(JsonNode details) {
    boolean minute = false;
    boolean day = false;
    if (!details.isArray()) return LimitType.UNKNOWN;
    for (JsonNode detail : details) {
      if (!detail.path("@type").asText().equals("type.googleapis.com/google.rpc.QuotaFailure"))
        continue;
      JsonNode violations = detail.path("violations");
      if (!violations.isArray()) continue;
      for (JsonNode violation : violations) {
        String id = violation.path("quotaId").asText();
        minute |= id.contains("PerMinute");
        day |= id.contains("PerDay");
      }
    }
    if (minute && day) return LimitType.MULTIPLE;
    if (minute) return LimitType.PER_MINUTE;
    if (day) return LimitType.PER_DAY;
    return LimitType.UNKNOWN;
  }
}
