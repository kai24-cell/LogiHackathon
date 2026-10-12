package jp.cheapreview.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import jp.cheapreview.conversation.dto.ConversationDtos.Imported;
import jp.cheapreview.conversation.dto.ConversationDtos.TextTurn;
import jp.cheapreview.conversation.dto.ConversationDtos.Type;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ConversationTextTest {
  final Instant now = Instant.parse("2026-10-12T00:00:00Z");

  @Test
  void unicodeCodeMarkersAndTrailingNewlinesRoundTrip() {
    var turns =
        List.of(new TextTurn("日本語😀\nUSER:\n\\先頭\n\n", "説明\n```java\nASSISTANT:\n\\\\x\n```\n"));
    String exported = ConversationText.export(null, turns, now);
    assertTrue(exported.startsWith("\uFEFFCHEAPREVIEW_CHAT_V1\n"));
    assertEquals(turns, ConversationText.parse(exported).turns());
    assertEquals(turns, ConversationText.parse(exported.replace("\n", "\r\n")).turns());
  }

  @Test
  void ordinaryTextDoesNotInferUserOrAssistantRoles() {
    String text = "参考資料\nUSER:\n質問らしい文字列\nASSISTANT:\n回答らしい文字列";
    var parsed = ConversationText.parse(text);
    assertEquals(Type.NOTE, parsed.type());
    assertEquals(text, parsed.note());
    assertTrue(parsed.turns().isEmpty());
  }

  @Test
  void noteAndNewTurnsRoundTripWithoutLosingEither() {
    var note = new Imported(Type.NOTE, "引継ぎ\n\\text\n", List.of());
    var turns = List.of(new TextTurn("今回の質問", "回答"));
    var parsed = ConversationText.parse(ConversationText.export(note, turns, now));
    assertEquals(note.note(), parsed.note());
    assertEquals(turns, parsed.turns());
    assertEquals(parsed, ConversationText.parse(ConversationText.export(parsed, List.of(), now)));
  }

  @Test
  void reservedNotePrefixInOrdinaryCompletedTurnIsNotMisidentified() {
    var turns = List.of(new TextTurn(ConversationText.NOTE + "普通の質問", "回答"));
    assertEquals(turns, ConversationText.parse(ConversationText.export(null, turns, now)).turns());
  }

  @Test
  void malformedClaimedFormatIsRejectedRatherThanConvertedToNote() {
    for (String body :
        List.of(
            "ASSISTANT:\n回答\n",
            "USER:\n質問\n",
            "USER:\n\nASSISTANT:\n回答\n",
            "USER:\n質問\nUSER:\n次\n")) {
      assertThrows(
          ResponseStatusException.class,
          () ->
              ConversationText.parse(
                  ConversationText.HEADER + "\nEXPORTED_AT=" + now + "\n" + body));
    }
    assertThrows(
        ResponseStatusException.class,
        () -> ConversationText.parse(ConversationText.HEADER + "bad"));
  }

  @Test
  void twentyTurnsAreAllowedAndTwentyOneAreRejected() {
    var turns =
        java.util.stream.IntStream.range(0, 20)
            .mapToObj(index -> new TextTurn("質問" + index, "回答"))
            .toList();
    assertEquals(
        20, ConversationText.parse(ConversationText.export(null, turns, now)).turns().size());
    var tooMany = new java.util.ArrayList<>(turns);
    tooMany.add(new TextTurn("追加", "回答"));
    assertThrows(
        ResponseStatusException.class,
        () -> ConversationText.parse(ConversationText.export(null, tooMany, now)));
  }

  @Test
  void onlyOwnReferenceLinesAreRecognizedWithoutResolvingAnyPath() {
    var imported =
        new Imported(
            Type.TRANSCRIPT,
            null,
            List.of(
                new TextTurn(
                    "質問",
                    "ref-1: src/Service.java 1–2行\nref-2: ../not-scanned.java 3–4行\nordinary.java mention\n")));
    assertEquals(
        java.util.Set.of("src/Service.java", "../not-scanned.java"),
        ConversationText.referencePaths(imported));
  }
}
