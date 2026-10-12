package jp.cheapreview.translation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.translation.dto.TranslationDtos;
import org.junit.jupiter.api.Test;

class TranslationServiceTest {
  @Test
  void sharedDeadlineKeepsCompletedFieldsAndDoesNotSendLaterBatches() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var client =
        client(
            contents -> {
              if (calls.incrementAndGet() == 1)
                return contents.stream().map(value -> "訳 " + value).toList();
              try {
                Thread.sleep(2000);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              return contents;
            });
    var original =
        new GenerationDtos.Answer(
            "a".repeat(200),
            List.of(new GenerationDtos.Section("b".repeat(200), "c".repeat(200), "", List.of())),
            List.of(),
            List.of("d".repeat(200)));
    var service = new TranslationService(client, 256, java.time.Duration.ofMillis(200));
    try {
      long started = System.nanoTime();
      var result = service.translate(original, List.of(), 1);
      assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
      assertTrue(result.partialTranslation());
      assertTrue(result.displayAnswer().summary().startsWith("訳 "));
      assertEquals(2, calls.get());
      assertEquals("TRANSLATION_TIMEOUT", result.errorCode());
    } finally {
      service.close();
    }
  }

  private GenerationDtos.Answer answer() {
    return new GenerationDtos.Answer(
        "OrderService calls save and `save()`.",
        List.of(
            new GenerationDtos.Section(
                "Flow", "See src/OrderService.java and ref-1.", "save();", List.of("ref-1"))),
        List.of(
            new GenerationDtos.Finding(
                GenerationDtos.Severity.HIGH,
                "Validation",
                "Reject invalid input.",
                "Add a test.",
                List.of("ref-1"))),
        List.of("No external dependencies checked."));
  }

  @Test
  void translatesOnlyExplanationsAndPreservesIdentifiersCodeAndReferences() {
    List<String> sent = new ArrayList<>();
    TranslationClient client =
        client(
            contents -> {
              sent.addAll(contents);
              return contents.stream().map(value -> "日本語 " + value).toList();
            });
    var original = answer();
    var result =
        new TranslationService(client, 10000)
            .translate(
                original, List.of("OrderService", "save", "src/OrderService.java", "ref-1"), 1);
    assertEquals(TranslationDtos.Status.SUCCEEDED, result.status());
    assertTrue(result.displayAnswer().summary().contains("OrderService"));
    assertTrue(result.displayAnswer().summary().contains("`save()`"));
    assertTrue(
        sent.stream()
            .noneMatch(
                text ->
                    text.contains("OrderService")
                        || text.contains("save()")
                        || text.contains("src/")));
    assertEquals(
        original.sections().getFirst().code(), result.displayAnswer().sections().getFirst().code());
    assertEquals(
        original.sections().getFirst().referenceIds(),
        result.displayAnswer().sections().getFirst().referenceIds());
    assertEquals(
        original.findings().getFirst().severity(),
        result.displayAnswer().findings().getFirst().severity());
    assertEquals("OrderService calls save and `save()`.", original.summary());
  }

  @Test
  void missingDuplicatedModifiedAndUnknownPlaceholdersFallBackToOriginal() {
    for (var corrupt :
        List.<java.util.function.UnaryOperator<String>>of(
            value -> value.replace("__CR_KEEP_0000__", ""),
            value -> value + "__CR_KEEP_0000__",
            value -> value.replace("__CR_KEEP_0000__", "__CR_KEEP_0000_"),
            value -> value + "__CR_KEEP_9999__")) {
      var result =
          new TranslationService(client(contents -> contents.stream().map(corrupt).toList()), 10000)
              .translate(answer(), List.of("OrderService"), 1);
      assertEquals(answer().summary(), result.displayAnswer().summary());
      assertTrue(result.warnings().stream().anyMatch(warning -> warning.field().equals("summary")));
    }
  }

  @Test
  void oneBrokenFieldDoesNotDiscardOtherTranslatedFields() {
    var result =
        new TranslationService(
                client(
                    contents -> {
                      var translated =
                          new ArrayList<>(contents.stream().map(value -> "訳 " + value).toList());
                      translated.set(0, "missing token");
                      return translated;
                    }),
                10000)
            .translate(answer(), List.of("OrderService"), 1);
    assertTrue(result.partialTranslation());
    assertEquals(answer().summary(), result.displayAnswer().summary());
    assertEquals("訳 Flow", result.displayAnswer().sections().getFirst().title());
  }

  @Test
  void noCredentialsReturnsEnglishWithoutCallingProvider() {
    var client =
        new TranslationClient() {
          public boolean configured() {
            return false;
          }

          public List<String> translate(List<String> contents) {
            fail("must not call provider");
            return List.of();
          }
        };
    var result = new TranslationService(client, 10000).translate(answer(), List.of(), 1);
    assertEquals(TranslationDtos.Status.FAILED, result.status());
    assertEquals(answer(), result.displayAnswer());
    assertEquals("TRANSLATION_NOT_READY", result.errorCode());
  }

  @Test
  void unicodeBatchesAndLongSentenceFallbackDoNotSplitIdentifiers() {
    List<List<String>> requests = new ArrayList<>();
    var service =
        new TranslationService(
            client(
                contents -> {
                  requests.add(contents);
                  return contents;
                }),
            256);
    String text = ("😀 ".repeat(30) + "Done. ").repeat(5);
    var original = new GenerationDtos.Answer(text, List.of(), List.of(), List.of("x".repeat(257)));
    var result = service.translate(original, List.of(), 1);
    assertEquals(text, result.displayAnswer().summary());
    assertTrue(
        requests.stream()
            .allMatch(
                contents ->
                    contents.stream()
                            .mapToInt(value -> value.codePointCount(0, value.length()))
                            .sum()
                        <= 256));
    assertTrue(
        result.warnings().stream()
            .anyMatch(warning -> warning.code().equals("TRANSLATION_TEXT_TOO_LONG")));
  }

  @Test
  void tokenCollisionsAndIdentifierBoundariesArePreserved() {
    String raw = "a save saved OrderService __CR_KEEP_0000__ `save()` `save()`";
    var text = ProtectedText.protect(raw, List.of("a", "save", "Order", "OrderService"));
    assertTrue(text.value().contains("saved"));
    assertFalse(text.value().contains("OrderService"));
    assertEquals(raw, text.restore(text.value()));
  }

  private TranslationClient client(java.util.function.Function<List<String>, List<String>> action) {
    return new TranslationClient() {
      public boolean configured() {
        return true;
      }

      public List<String> translate(List<String> contents) {
        return action.apply(contents);
      }
    };
  }
}
