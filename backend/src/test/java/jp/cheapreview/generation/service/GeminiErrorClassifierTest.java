package jp.cheapreview.generation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import jp.cheapreview.generation.dto.GenerationDtos.ApiStage;
import jp.cheapreview.generation.dto.GenerationDtos.ApiStatus;
import jp.cheapreview.generation.dto.GenerationDtos.LimitType;
import org.junit.jupiter.api.Test;

class GeminiErrorClassifierTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private GenerationFailure classify(int status, String json) throws Exception {
    return GeminiErrorClassifier.classify(status, mapper.readTree(json), ApiStage.MODEL_INFO);
  }

  @Test
  void modelNotFoundKeepsHttpAndStageWithoutGuessingRetirement() throws Exception {
    var failure =
        classify(
            404,
            "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"fake-only-key legacy model\"}}");
    assertEquals("MODEL_NOT_FOUND", failure.getMessage());
    assertEquals(404, failure.details().httpStatus());
    assertEquals(ApiStatus.NOT_FOUND, failure.details().apiStatus());
    assertEquals(ApiStage.MODEL_INFO, failure.details().stage());
    assertFalse(mapper.writeValueAsString(failure.details()).contains("fake-only-key"));
    assertNull(failure.getCause());
  }

  @Test
  void unsupportedRequestPrerequisiteBillingAndServerAreDistinct() throws Exception {
    assertEquals(
        "GEMINI_INVALID_REQUEST",
        classify(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}").getMessage());
    assertEquals(
        "GEMINI_PRECONDITION_FAILED",
        classify(400, "{\"error\":{\"status\":\"FAILED_PRECONDITION\"}}").getMessage());
    assertEquals("GEMINI_BILLING_REQUIRED", classify(402, "{}").getMessage());
    assertEquals("GEMINI_SERVER_ERROR", classify(500, "{}").getMessage());
    assertEquals("GENERATION_TIMEOUT", classify(504, "{}").getMessage());
  }

  @Test
  void invalidKeyOn400IsAuthenticationRatherThanModelError() throws Exception {
    var failure =
        classify(
            400,
            "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"API_KEY_INVALID\",\"metadata\":{\"secret\":\"fake-only-key\"}}]}}");
    assertEquals("GEMINI_AUTH_FAILED", failure.getMessage());
    assertFalse(mapper.writeValueAsString(failure.details()).contains("secret"));
  }

  @Test
  void quotaTimeUnitsAreNormalizedAndUnknownStatusNeverEchoes() throws Exception {
    for (String unit : new String[] {"PerMinute", "PerDay", "Unrecognized"}) {
      var failure =
          classify(
              429,
              "{\"error\":{\"status\":\"fake-only-key\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":\"GenerateRequests"
                  + unit
                  + "PerProject\",\"description\":\"fake-only-key\"}]}]}}");
      assertEquals("GEMINI_QUOTA_EXCEEDED", failure.getMessage());
      assertEquals(ApiStatus.UNKNOWN, failure.details().apiStatus());
      assertEquals(
          unit.equals("PerMinute")
              ? LimitType.PER_MINUTE
              : unit.equals("PerDay") ? LimitType.PER_DAY : LimitType.UNKNOWN,
          failure.details().limitType());
      assertFalse(mapper.writeValueAsString(failure.details()).contains("fake-only-key"));
    }
    assertEquals("GEMINI_RATE_LIMITED", classify(429, "{}").getMessage());
    assertEquals(LimitType.UNKNOWN, classify(429, "{}").details().limitType());
  }
}
