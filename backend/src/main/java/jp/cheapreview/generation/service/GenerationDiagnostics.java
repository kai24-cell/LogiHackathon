package jp.cheapreview.generation.service;

import java.time.Instant;
import java.util.UUID;
import jp.cheapreview.generation.dto.GenerationDtos.ApiStage;
import jp.cheapreview.generation.dto.GenerationDtos.ApiStatus;
import jp.cheapreview.generation.dto.GenerationDtos.ErrorDetails;
import jp.cheapreview.generation.dto.GenerationDtos.LimitType;
import jp.cheapreview.generation.dto.GenerationDtos.TimeoutType;
import org.slf4j.LoggerFactory;

/** 認証・URL・本文を受け取らず、定型値と時刻だけを記録する通信診断。 */
final class GenerationDiagnostics {
  private final String id = UUID.randomUUID().toString();
  private final ApiStage stage;
  private final long started = System.nanoTime();
  private volatile Integer status;
  private volatile TimeoutType timeoutType = TimeoutType.NONE;

  GenerationDiagnostics(ApiStage stage) {
    this.stage = stage;
    log("START", 0);
  }

  void headers(int status) {
    this.status = status;
    log("HEADERS", elapsed());
  }

  GenerationFailure timeout(TimeoutType type) {
    timeoutType = type;
    // キャンセルが直ちに完了しない場合も、期限到達をその場で記録する。
    log("TIMEOUT", elapsed());
    return new GenerationFailure(
        "GENERATION_TIMEOUT",
        new ErrorDetails(status, ApiStatus.UNKNOWN, stage, LimitType.UNKNOWN, type, elapsed()));
  }

  /** 接続失敗の明示的な例外だけCONNECTとし、総待機期限から接続原因を推測しない。 */
  static TimeoutType classifyTimeout(Throwable cause) {
    return cause instanceof java.net.http.HttpConnectTimeoutException
        ? TimeoutType.CONNECT
        : TimeoutType.RESPONSE_WAIT;
  }

  void end() {
    log("END", elapsed());
  }

  private long elapsed() {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  /** 経過時間は単調時計、開始・終了の絶対時刻はUTC。未取得HTTP値はnull。 */
  private void log(String event, long elapsed) {
    LoggerFactory.getLogger(GenerationDiagnostics.class)
        .info(
            "API_DIAGNOSTIC id={} stage={} event={} at={} elapsedMs={} httpStatus={} timeoutType={}",
            id,
            stage,
            event,
            Instant.now(),
            elapsed,
            status,
            timeoutType);
  }
}
