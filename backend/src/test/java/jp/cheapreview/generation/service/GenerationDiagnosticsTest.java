package jp.cheapreview.generation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jp.cheapreview.generation.dto.GenerationDtos.ApiStage;
import jp.cheapreview.generation.dto.GenerationDtos.TimeoutType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class GenerationDiagnosticsTest {
  @Test
  void logsOnlyStageTimesStatusAndFixedTimeoutType() {
    var logger = (Logger) LoggerFactory.getLogger(GenerationDiagnostics.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var diagnostic = new GenerationDiagnostics(ApiStage.JOB);
      var failure = diagnostic.timeout(TimeoutType.JOB_DEADLINE);
      diagnostic.end();
      assertEquals(ApiStage.JOB, failure.details().stage());
      assertEquals(TimeoutType.JOB_DEADLINE, failure.details().timeoutType());
      assertTrue(failure.details().elapsedMs() >= 0);
      assertEquals(3, appender.list.size());
      assertTrue(appender.list.get(1).getFormattedMessage().contains("event=TIMEOUT"));
      String log = appender.list.get(2).getFormattedMessage();
      assertTrue(log.contains("event=END"));
      assertTrue(log.contains("at="));
      assertTrue(log.contains("httpStatus=null"));
      assertTrue(log.contains("timeoutType=JOB_DEADLINE"));
      assertFalse(log.contains("body"));
      assertFalse(log.contains("key"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void distinguishesConnectionFromResponseTimeoutWithoutLoggingExceptionMessage() {
    assertEquals(
        TimeoutType.CONNECT,
        GenerationDiagnostics.classifyTimeout(
            new java.net.http.HttpConnectTimeoutException("secret-sentinel")));
    assertEquals(
        TimeoutType.RESPONSE_WAIT,
        GenerationDiagnostics.classifyTimeout(
            new java.net.http.HttpTimeoutException("secret-sentinel")));
  }
}
