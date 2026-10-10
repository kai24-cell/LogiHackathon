package jp.cheapreview.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import jp.cheapreview.budget.service.PromptMaterializer;
import jp.cheapreview.generation.dto.GenerationDtos.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 固定HTTPS宛先へ1回だけ送信する。認証はヘッダー、redirectと自動再試行は無効。 */
@Component
public class GeminiHttpClient implements GeminiClient {
  private static final int MAX_RESPONSE_BYTES = 1_048_576;
  private final ObjectMapper mapper;
  private final URI base;
  private final Duration timeout;
  private final HttpClient http;

  @Autowired
  public GeminiHttpClient(
      ObjectMapper mapper, @Value("${cheapreview.generation.timeout-seconds:120}") int seconds) {
    this(mapper, URI.create("https://generativelanguage.googleapis.com/v1beta/"), seconds);
  }

  // テストだけがローカルstubへ差し替える。利用者から宛先を受け付けない。
  GeminiHttpClient(ObjectMapper mapper, URI base, int seconds) {
    if (seconds < 1 || seconds > 120)
      throw new IllegalArgumentException("Invalid generation timeout");
    this.mapper = mapper;
    this.base = base;
    timeout = Duration.ofSeconds(seconds);
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** 公開モデル情報で入出力上限とgenerateContent対応を確認してから、課金対象のPOSTを行う。 */
  @Override
  public Reply generate(
      PromptMaterializer.Bundle prompt,
      GenerationSettings.Credentials credentials,
      long requiredInputTokens,
      long maximumOutputTokens) {
    String model = credentials.modelId();
    if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}"))
      throw new GenerationFailure("MODEL_UNSUPPORTED");
    var info = exchange("models/" + model, null, credentials.key());
    boolean supports = false;
    for (var method : info.path("supportedGenerationMethods"))
      if (method.asText().equals("generateContent")) supports = true;
    long inputLimit = info.path("inputTokenLimit").asLong(0);
    long outputLimit = info.path("outputTokenLimit").asLong(0);
    if (!supports || inputLimit < 1 || outputLimit < 1)
      throw new GenerationFailure("MODEL_UNSUPPORTED");
    if (requiredInputTokens > inputLimit || maximumOutputTokens > outputLimit)
      throw new GenerationFailure("MODEL_LIMIT_EXCEEDED");

    var body = mapper.createObjectNode();
    body.set("systemInstruction", content(prompt.system(), false));
    body.putArray("contents").add(content(prompt.contents(), true));
    var config = body.putObject("generationConfig");
    config.put("temperature", 0.2);
    config.put("maxOutputTokens", maximumOutputTokens);
    config.put("candidateCount", 1);
    config.put("responseMimeType", "application/json");
    try {
      config.set("responseJsonSchema", mapper.readTree(prompt.schema()));
    } catch (Exception exception) {
      throw new GenerationFailure("PROMPT_INVALID");
    }
    return decode(
        exchange("models/" + model + ":generateContent", body.toString(), credentials.key()),
        credentials.key());
  }

  private JsonNode content(String text, boolean user) {
    var content = mapper.createObjectNode();
    if (user) content.put("role", "user");
    content.putArray("parts").addObject().put("text", text);
    return content;
  }

  /** 生のHTTPエラーはキーやコードを含み得るため、固定コードへ変換して破棄する。 */
  private JsonNode exchange(String path, String body, String key) {
    try {
      var builder =
          HttpRequest.newBuilder(base.resolve(path))
              .timeout(timeout)
              .header("x-goog-api-key", key)
              .header("Content-Type", "application/json");
      if (body == null) builder.GET();
      else builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      var pending = http.sendAsync(builder.build(), ignored -> new LimitedBody());
      HttpResponse<String> response;
      try {
        // ヘッダー到着後にbodyが停止する場合も、通信全体へtimeoutを適用する。
        response = pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException exception) {
        pending.cancel(true);
        throw new GenerationFailure("GENERATION_TIMEOUT");
      } catch (InterruptedException exception) {
        pending.cancel(true);
        throw exception;
      } catch (java.util.concurrent.ExecutionException exception) {
        if (exception.getCause() instanceof java.net.http.HttpTimeoutException)
          throw new GenerationFailure("GENERATION_TIMEOUT");
        throw new GenerationFailure("GEMINI_UNAVAILABLE");
      }
      int status = response.statusCode();
      if (status == 401 || status == 403) throw new GenerationFailure("GEMINI_AUTH_FAILED");
      if (status == 429) throw new GenerationFailure("GEMINI_RATE_LIMITED");
      if (status == 400 || status == 404) throw new GenerationFailure("MODEL_UNSUPPORTED");
      if (status < 200 || status >= 300) throw new GenerationFailure("GEMINI_UNAVAILABLE");
      return mapper.readTree(response.body());
    } catch (GenerationFailure failure) {
      throw failure;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new GenerationFailure("CANCELLED");
    } catch (Exception exception) {
      throw new GenerationFailure("GEMINI_UNAVAILABLE");
    }
  }

  private Reply decode(JsonNode response, String key) {
    if (!response.path("promptFeedback").path("blockReason").asText().isEmpty())
      throw new GenerationFailure("GEMINI_BLOCKED");
    var candidates = response.path("candidates");
    if (!candidates.isArray() || candidates.size() != 1)
      throw new GenerationFailure("INVALID_ANSWER");
    var candidate = candidates.get(0);
    String finish = candidate.path("finishReason").asText();
    if (finish.equals("MAX_TOKENS")) throw new GenerationFailure("OUTPUT_LIMIT");
    if (!finish.equals("STOP")) throw new GenerationFailure("GEMINI_BLOCKED");
    var text = new StringBuilder();
    for (var part : candidate.path("content").path("parts"))
      if (!part.path("thought").asBoolean(false) && part.path("text").isTextual())
        text.append(part.get("text").asText());
    // モデルが認証ヘッダー由来の値を返す異常系でも、保持・応答へキーを残さない。
    String raw = SecretRedaction.redact(text.toString(), key);
    var usage = response.path("usageMetadata");
    return new Reply(
        raw,
        new Usage(
            value(usage, "promptTokenCount"),
            value(usage, "candidatesTokenCount"),
            value(usage, "thoughtsTokenCount"),
            value(usage, "cachedContentTokenCount"),
            value(usage, "totalTokenCount")),
        finish,
        null);
  }

  private Long value(JsonNode node, String field) {
    var value = node.path(field);
    return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0
        ? value.asLong()
        : null;
  }

  /** 分割レスポンスでも累積容量を制限し、巨大なエラー本文をメモリへ保持しない。 */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> completed = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    public CompletionStage<String> getBody() {
      return completed;
    }

    public void onSubscribe(Flow.Subscription supplied) {
      subscription = supplied;
      supplied.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
          subscription.cancel();
          completed.completeExceptionally(new GenerationFailure("RESPONSE_TOO_LARGE"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    public void onError(Throwable ignored) {
      completed.completeExceptionally(new GenerationFailure("GEMINI_UNAVAILABLE"));
    }

    public void onComplete() {
      completed.complete(bytes.toString(StandardCharsets.UTF_8));
    }
  }
}
