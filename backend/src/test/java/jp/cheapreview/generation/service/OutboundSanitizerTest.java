package jp.cheapreview.generation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import jp.cheapreview.budget.dto.BudgetPreview;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OutboundSanitizerTest {
  private BudgetPreview.Chunk chunk(String code) {
    return new BudgetPreview.Chunk(
        "f",
        "A.java",
        true,
        List.of(),
        List.of(new BudgetPreview.Range(1, 2)),
        List.of(),
        code,
        List.of());
  }

  @Test
  void namedCredentialsAreMaskedWithoutChangingLinesOrReferences() {
    var before =
        chunk(
            "String dbPassword = \"fixture-secret\";\r\nmap.put(\"apiKey\", \"another-fixture\"); @Value(\"${db.password:fixture-default}\") String value;");
    var after = OutboundSanitizer.sanitize(before);
    assertFalse(after.code().contains("fixture-secret"));
    assertFalse(after.code().contains("another-fixture"));
    assertFalse(after.code().contains("fixture-default"));
    assertTrue(after.code().contains("[REDACTED]"));
    assertTrue(after.code().contains("\r\n"));
    assertEquals(before.ranges(), after.ranges());
    assertFalse(after.reasons().isEmpty());
  }

  @Test
  void unresolvedPemTokensTextBlockAndQuestionStopInsteadOfLeaking() {
    for (String code :
        List.of(
            "String x=\"-----BEGIN PRIVATE KEY-----\";",
            "String x=\"AIza" + "a".repeat(35) + "\";",
            "String password = \"\"\"\nfixture\n\"\"\";"))
      assertThrows(ResponseStatusException.class, () -> OutboundSanitizer.sanitize(chunk(code)));
    assertThrows(
        ResponseStatusException.class, () -> OutboundSanitizer.checkQuestion("password: fixture"));
  }
}
