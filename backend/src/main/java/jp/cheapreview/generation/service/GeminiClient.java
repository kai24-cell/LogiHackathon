package jp.cheapreview.generation.service;

import jp.cheapreview.budget.service.PromptMaterializer;
import jp.cheapreview.generation.dto.GenerationDtos.Usage;

/** 外部通信だけを差し替え、CIでは実APIを呼ばないための境界。 */
public interface GeminiClient {
  record Reply(String rawText, Usage usage, String finishReason, String providerRequestId) {
    @Override
    public String toString() {
      return "GeminiReply[REDACTED]";
    }
  }

  Reply generate(
      PromptMaterializer.Bundle prompt,
      GenerationSettings.Credentials credentials,
      long requiredInputTokens,
      long maximumOutputTokens);
}
