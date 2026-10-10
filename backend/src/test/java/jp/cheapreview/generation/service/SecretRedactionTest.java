package jp.cheapreview.generation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SecretRedactionTest {
  @Test
  void masksMixedEscapesWithoutChangingOtherTextOrLiteralCase() {
    String raw = "prefix Ab-c \\u0041b\\u002Dc suffix AB-C";
    assertEquals("prefix [REDACTED] [REDACTED] suffix AB-C", SecretRedaction.redact(raw, "Ab-c"));
    assertEquals(raw, SecretRedaction.redact(raw, null));
    assertEquals(raw, SecretRedaction.redact(raw, ""));
  }
}
