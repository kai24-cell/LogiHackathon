package jp.cheapreview.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import jp.cheapreview.budget.service.BudgetSettings;
import jp.cheapreview.budget.service.TokenEstimator;
import jp.cheapreview.conversation.dto.ConversationDtos;
import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.generation.dto.SettingsDtos;
import jp.cheapreview.generation.service.GenerationSettings;
import jp.cheapreview.search.dto.CodeSearch.Mode;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ConversationServiceTest {
  final TokenEstimator estimator =
      new TokenEstimator(
          new BudgetSettings(
              new BigDecimal(".35"),
              new BigDecimal("1.50"),
              BigDecimal.ONE,
              new BigDecimal(".20"),
              512,
              2048,
              3,
              100,
              Map.of()));
  final GenerationSettings settings = mock(GenerationSettings.class);
  final ConversationService service =
      new ConversationService(
          mock(WorkspaceService.class), estimator, settings, new ObjectMapper(), 4, 4);

  ConversationServiceTest() {
    when(settings.status())
        .thenReturn(
            new SettingsDtos.Status("fixture", false, "v", SettingsDtos.Source.NONE, false, false));
  }

  GenerationDtos.Result result(String question) {
    return new GenerationDtos.Result(
        new GenerationDtos.Answer("Explanation", List.of(), List.of(), List.of()),
        List.of(),
        List.of(),
        new GenerationDtos.Usage(null, null, null, null, null),
        100,
        1,
        "STOP",
        null,
        "fixture",
        "snapshot",
        question,
        Mode.SERVICE_REVIEW,
        "w",
        SettingsDtos.Source.STUB);
  }

  @Test
  void twentyTurnsRetainedSixLatestSelectedAndDuplicateJobNotAdded() {
    var view = service.create("w");
    for (int index = 0; index < 22; index++)
      service.commit(view.conversationId(), index, "job" + index, result("question" + index));
    var current = service.get(view.conversationId());
    assertEquals(20, current.turns().size());
    assertEquals("question2", current.turns().getFirst().question());
    service.commit(view.conversationId(), 22, "job21", result("duplicate"));
    assertEquals(20, service.get(view.conversationId()).turns().size());
    var history = service.select(view.conversationId(), "w", 100000, text -> true);
    assertEquals(6, history.included());
    assertEquals(14, history.omitted());
    assertTrue(history.text().contains("question16"));
    assertTrue(!history.text().contains("question15"));
    assertTrue(history.text().contains("ASSISTANT_ENGLISH_JSON"));
  }

  @Test
  void latestTurnCannotBeSilentlyOmittedWhenBudgetOrWholePromptDoesNotFit() {
    var view = service.create("w");
    service.commit(view.conversationId(), 0, "job", result("x".repeat(1000)));
    assertThrows(
        ResponseStatusException.class,
        () -> service.select(view.conversationId(), "w", 1024, text -> true));
    assertThrows(
        ResponseStatusException.class,
        () -> service.select(view.conversationId(), "w", 100000, text -> false));
  }

  @Test
  void importRequiresExplicitNewConversationAndIsSingleUse() {
    var old = service.create("w");
    var preview =
        service.preview(
            new ConversationDtos.PreviewRequest("以前の資料", ConversationDtos.InputKind.PASTE));
    assertEquals(0, service.get(old.conversationId()).version());
    var imported = service.importConversation("w", preview.importId());
    assertTrue(!old.conversationId().equals(imported.conversationId()));
    var history = service.select(imported.conversationId(), "w", 8192, text -> true);
    assertTrue(history.text().contains("USER_IMPORT_NOTE"));
    assertThrows(
        ResponseStatusException.class, () -> service.importConversation("w", preview.importId()));
    assertThrows(
        ResponseStatusException.class,
        () -> service.select(imported.conversationId(), "other", 8192, text -> true));
  }

  @Test
  void clearAndVersionChangesRejectLateCommitAndOldPreview() {
    var view = service.create("w");
    service.commit(view.conversationId(), 0, "job", result("question"));
    assertThrows(
        ResponseStatusException.class, () -> service.checkVersion(view.conversationId(), 0));
    service.delete(view.conversationId());
    assertThrows(
        ResponseStatusException.class,
        () -> service.commit(view.conversationId(), 1, "late", result("late")));
  }

  @Test
  void importLimitsSurrogatesAndKnownCredentialsAreRejectedWithoutEcho() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.preview(
                new ConversationDtos.PreviewRequest(
                    "x".repeat(100001), ConversationDtos.InputKind.PASTE)));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.preview(
                new ConversationDtos.PreviewRequest("x\uD800", ConversationDtos.InputKind.FILE)));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.preview(
                new ConversationDtos.PreviewRequest(
                    "api_key=do-not-export", ConversationDtos.InputKind.PASTE)));
    var accepted =
        service.preview(
            new ConversationDtos.PreviewRequest(
                "x".repeat(100000), ConversationDtos.InputKind.PASTE));
    assertEquals(100000, accepted.codePoints());
  }

  @Test
  void displayUpdateDoesNotChangeEnglishHistoryOrDuplicateTurn() {
    var view = service.create("w");
    service.commit(view.conversationId(), 0, "job", result("質問"));
    service.updateDisplay(
        view.conversationId(),
        "job",
        new GenerationDtos.Answer("日本語訳", List.of(), List.of(), List.of()),
        false);
    assertTrue(service.export(view.conversationId()).contains("日本語訳"));
    assertTrue(!service.export(view.conversationId()).contains("未翻訳"));
    assertTrue(
        service
            .select(view.conversationId(), "w", 8192, text -> true)
            .text()
            .contains("Explanation"));
    assertEquals(1, service.get(view.conversationId()).turns().size());
  }

  @Test
  void unknownOrUncommittedTranslationJobDoesNotChangeConversationVersion() {
    var view = service.create("w");
    service.commit(view.conversationId(), 0, "committed", result("質問"));
    var before = service.get(view.conversationId());
    service.updateDisplay(
        view.conversationId(),
        "cancelled-or-unknown",
        new GenerationDtos.Answer("遅延訳", List.of(), List.of(), List.of()),
        false);
    assertEquals(before, service.get(view.conversationId()));
  }

  @Test
  void expiredOrClearedConversationIgnoresLateTranslationEvent() {
    var now = java.time.Instant.parse("2026-10-12T00:00:00Z");
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "clock", java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    var view = service.create("w");
    service.commit(view.conversationId(), 0, "job", result("question"));
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "clock", java.time.Clock.fixed(now.plusSeconds(7201), java.time.ZoneOffset.UTC));
    var event =
        new jp.cheapreview.generation.event.TranslationDisplayUpdated(
            "job", result("q").answerEnglish(), false);
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.translated(event));
    assertThrows(ResponseStatusException.class, () -> service.get(view.conversationId()));
    var fresh = service.create("w");
    service.commit(fresh.conversationId(), 0, "fresh", result("q"));
    service.delete(fresh.conversationId());
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () ->
            service.translated(
                new jp.cheapreview.generation.event.TranslationDisplayUpdated(
                    "fresh", result("q").answerEnglish(), true)));
  }

  @Test
  void httpsIsExportableButWindowsDriveAndUncPathsAreRejected() {
    var view = service.create("w");
    service.commit(view.conversationId(), 0, "url", result("See https://example.com/docs"));
    assertTrue(service.export(view.conversationId()).contains("https://example.com/docs"));
    for (String path : List.of("C:\\project\\code", "C:/project/code", "\\\\server\\share\\code")) {
      var unsafe = service.create("w");
      service.commit(unsafe.conversationId(), 0, path, result(path));
      assertEquals(
          "EXPORT_REQUIRES_REVIEW",
          assertThrows(ResponseStatusException.class, () -> service.export(unsafe.conversationId()))
              .getReason());
    }
  }

  @Test
  void expiredImportAndConversationAreRemovedWithoutWaitingInRealTime() {
    var now = java.time.Instant.parse("2026-10-12T00:00:00Z");
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "clock", java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    var view = service.create("w");
    var preview =
        service.preview(
            new ConversationDtos.PreviewRequest("reference", ConversationDtos.InputKind.PASTE));
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "clock", java.time.Clock.fixed(now.plusSeconds(301), java.time.ZoneOffset.UTC));
    assertEquals(
        "STALE_IMPORT",
        assertThrows(
                ResponseStatusException.class,
                () -> service.importConversation("w", preview.importId()))
            .getReason());
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "clock", java.time.Clock.fixed(now.plusSeconds(7201), java.time.ZoneOffset.UTC));
    assertEquals(
        "CONVERSATION_NOT_FOUND",
        assertThrows(ResponseStatusException.class, () -> service.get(view.conversationId()))
            .getReason());
    assertTrue(service.create("w").turns().isEmpty());
  }

  @Test
  void twentyFivePercentTokenBoundaryIsInclusiveAndOneLessIsRejected() {
    var view = service.create("w");
    service.commit(view.conversationId(), 0, "job", result("x".repeat(2000)));
    long tokens = service.select(view.conversationId(), "w", 100000, text -> true).tokens();
    assertEquals(
        1, service.select(view.conversationId(), "w", tokens * 4, text -> true).included());
    assertEquals(
        "HISTORY_TOO_LARGE",
        assertThrows(
                ResponseStatusException.class,
                () -> service.select(view.conversationId(), "w", tokens * 4 - 1, text -> true))
            .getReason());
  }
}
