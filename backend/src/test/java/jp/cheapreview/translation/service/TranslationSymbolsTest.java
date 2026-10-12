package jp.cheapreview.translation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import jp.cheapreview.budget.dto.BudgetPreview;
import jp.cheapreview.generation.dto.GenerationDtos;
import org.junit.jupiter.api.Test;

class TranslationSymbolsTest {
  @Test
  void protectsNamesInDeclarationOnlyChunksWithoutReadingOtherFiles() {
    String code =
        "class OrderService { void selected() {} }\n"
            + "// declaration only, methodId=notASymbol\n"
            + "public java.util.List<Order> omittedMethod(String customerName) { /* body omitted */\n";
    var chunk =
        new BudgetPreview.Chunk(
            "f", "src/OrderService.java", true, List.of(), List.of(), List.of(), code, List.of());
    var symbols = TranslationSymbols.from(List.of(chunk));
    assertTrue(
        symbols.containsAll(List.of("OrderService", "omittedMethod", "customerName", "Order")));
    assertFalse(symbols.contains("notASymbol"));
    List<String> sent = new java.util.ArrayList<>();
    var client =
        new TranslationClient() {
          public boolean configured() {
            return true;
          }

          public List<String> translate(List<String> contents) {
            sent.addAll(contents);
            return contents;
          }
        };
    var english =
        new GenerationDtos.Answer(
            "omittedMethod uses customerName.", List.of(), List.of(), List.of());
    var service = new TranslationService(client, 10000);
    try {
      assertEquals(english, service.translate(english, symbols, 1).displayAnswer());
      assertTrue(
          sent.stream()
              .noneMatch(text -> text.contains("omittedMethod") || text.contains("customerName")));
    } finally {
      service.close();
    }
  }

  @Test
  void doesNotTreatCommentsLiteralsAndTextBlocksAsIdentifiers() {
    var chunk =
        new BudgetPreview.Chunk(
            "f",
            "C.java",
            true,
            List.of(),
            List.of(),
            List.of(),
            "class C { String value = \"literalWord\"; String block = \"\"\"\ntextBlockWord\n\"\"\"; // commentWord\n }",
            List.of());
    var symbols = TranslationSymbols.from(List.of(chunk));
    assertFalse(symbols.contains("literalWord"));
    assertFalse(symbols.contains("textBlockWord"));
    assertFalse(symbols.contains("commentWord"));
    assertFalse(symbols.contains("class"));
  }
}
