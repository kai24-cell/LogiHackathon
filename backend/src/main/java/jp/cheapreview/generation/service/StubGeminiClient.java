package jp.cheapreview.generation.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jp.cheapreview.budget.service.PromptMaterializer;
import jp.cheapreview.generation.dto.GenerationDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** HTTPクライアントを持たない固定provider。通常ジョブの予算・回答検証は省略しない。 */
@Component
@ConditionalOnProperty(name = "cheapreview.generation.provider", havingValue = "stub")
public class StubGeminiClient implements GeminiClient {
  private final String scenario;
  private final String answer;

  public StubGeminiClient(@Value("${cheapreview.stub.scenario:normal}") String scenario) {
    if (!java.util.Set.of("normal", "invalid", "timeout").contains(scenario))
      throw new IllegalArgumentException("Invalid stub scenario");
    this.scenario = scenario;
    try (var stream = getClass().getResourceAsStream("/demo/answer-en.json")) {
      if (stream == null) throw new IllegalStateException("Missing stub fixture");
      answer = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Invalid stub fixture");
    }
  }

  @Override
  public Reply generate(
      PromptMaterializer.Bundle prompt,
      GenerationSettings.Credentials credentials,
      long requiredInputTokens,
      long maximumOutputTokens) {
    if (scenario.equals("timeout")) throw new GenerationFailure("GENERATION_TIMEOUT");
    // 実測usageを捏造しない。schema不正ケースもジョブの通常検証へ渡す。
    return new Reply(
        scenario.equals("invalid") ? "{}" : answer,
        new GenerationDtos.Usage(null, null, null, null, null),
        "STOP",
        null);
  }
}
