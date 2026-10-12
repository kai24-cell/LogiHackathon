package jp.cheapreview.generation.dto;

import java.time.Instant;
import java.util.List;
import jp.cheapreview.budget.dto.BudgetPreview;
import jp.cheapreview.translation.dto.TranslationDtos;

/** 翻訳・会話の後続処理でも英語回答と参照表を再利用できる型付き契約。 */
public final class GenerationDtos {
  private GenerationDtos() {}

  public record Start(
      String previewId, String requestId, String settingsVersion, BudgetPreview.Request input) {}

  public record Started(String jobId) {}

  public enum State {
    QUEUED,
    SCANNING,
    GENERATING,
    TRANSLATING,
    TRANSLATION_FAILED,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public enum Severity {
    INFO,
    LOW,
    MEDIUM,
    HIGH
  }

  public enum ApiStage {
    MODEL_INFO,
    GENERATE,
    JOB
  }

  public enum ApiStatus {
    INVALID_ARGUMENT,
    FAILED_PRECONDITION,
    PERMISSION_DENIED,
    UNAUTHENTICATED,
    NOT_FOUND,
    RESOURCE_EXHAUSTED,
    UNAVAILABLE,
    INTERNAL,
    DEADLINE_EXCEEDED,
    UNIMPLEMENTED,
    UNKNOWN
  }

  public enum LimitType {
    PER_MINUTE,
    PER_DAY,
    MULTIPLE,
    UNKNOWN
  }

  /** 外部の任意文字列を保持せず、HTTP数値と定型enumだけを公開する。 */
  public enum TimeoutType {
    NONE,
    CONNECT,
    RESPONSE_WAIT,
    JOB_DEADLINE
  }

  public record ErrorDetails(
      Integer httpStatus,
      ApiStatus apiStatus,
      ApiStage stage,
      LimitType limitType,
      TimeoutType timeoutType,
      Long elapsedMs) {
    public ErrorDetails(
        Integer httpStatus, ApiStatus apiStatus, ApiStage stage, LimitType limitType) {
      this(httpStatus, apiStatus, stage, limitType, TimeoutType.NONE, null);
    }
  }

  public record Section(String title, String body, String code, List<String> referenceIds) {}

  public record Finding(
      Severity severity,
      String title,
      String explanation,
      String suggestion,
      List<String> referenceIds) {}

  public record Answer(
      String summary, List<Section> sections, List<Finding> findings, List<String> limitations) {}

  public record Reference(
      String referenceId, String fileId, String relativePath, List<BudgetPreview.Range> ranges) {}

  public record Usage(
      Long promptTokens,
      Long outputTokens,
      Long thoughtTokens,
      Long cachedTokens,
      Long totalTokens) {}

  public record Result(
      Answer answerEnglish,
      List<Reference> references,
      List<String> warnings,
      Usage usage,
      long estimatedPromptTokens,
      long generationMs,
      String finishReason,
      String providerRequestId,
      String modelId,
      String snapshotId,
      String question,
      jp.cheapreview.search.dto.CodeSearch.Mode mode,
      String workspaceId,
      SettingsDtos.Source source) {}

  public record Job(
      String jobId,
      State state,
      String errorCode,
      Result result,
      Instant createdAt,
      ErrorDetails errorDetails,
      TranslationDtos.Result translation) {
    public Job(
        String jobId,
        State state,
        String errorCode,
        Result result,
        Instant createdAt,
        ErrorDetails errorDetails) {
      this(jobId, state, errorCode, result, createdAt, errorDetails, null);
    }

    public Job(String jobId, State state, String errorCode, Result result, Instant createdAt) {
      this(jobId, state, errorCode, result, createdAt, null);
    }
  }
}
