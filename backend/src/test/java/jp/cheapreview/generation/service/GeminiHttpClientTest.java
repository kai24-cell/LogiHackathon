package jp.cheapreview.generation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jp.cheapreview.budget.service.PromptMaterializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GeminiHttpClientTest {
  HttpServer server;
  java.util.concurrent.ExecutorService executor;
  GeminiHttpClient client;
  ObjectMapper mapper = new ObjectMapper();
  AtomicInteger posts = new AtomicInteger();
  AtomicReference<String> postBody = new AtomicReference<>();
  AtomicReference<String> keyHeader = new AtomicReference<>();
  int status = 200;
  int postStatus = 200;
  String metadata =
      "{\"inputTokenLimit\":8192,\"outputTokenLimit\":2048,\"supportedGenerationMethods\":[\"generateContent\"]}";
  String response =
      "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{\\\"summary\\\":\\\"ok\\\"}\"}]}}],\"usageMetadata\":{\"promptTokenCount\":12,\"totalTokenCount\":20}}";
  long delay;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/models/fixture-model",
        exchange -> {
          try {
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            assertNull(exchange.getRequestURI().getQuery());
            boolean post = exchange.getRequestMethod().equals("POST");
            if (post) {
              posts.incrementAndGet();
              postBody.set(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            if (delay > 0) Thread.sleep(delay);
            byte[] bytes = (post ? response : metadata).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(post && status == 200 ? postStatus : status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Exception ignored) {
            /* timeout/cancel closes the test socket */
          } finally {
            exchange.close();
          }
        });
    server.start();
    client =
        new GeminiHttpClient(
            mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), 1);
  }

  @AfterEach
  void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private GeminiClient.Reply generate() {
    return client.generate(
        new PromptMaterializer.Bundle("role", "{\"type\":\"object\"}", "日本語質問"),
        new GenerationSettings.Credentials("fixture-model", "fake-only-key", "v"),
        4800,
        2048);
  }

  @Test
  void realAdapterUsesHeadersSchemaLimitsAndExactlyOnePost() throws Exception {
    var reply = generate();
    assertEquals(1, posts.get());
    assertEquals("fake-only-key", keyHeader.get());
    assertFalse(postBody.get().contains("fake-only-key"));
    var sent = mapper.readTree(postBody.get());
    assertEquals("日本語質問", sent.path("contents").get(0).path("parts").get(0).path("text").asText());
    assertEquals("role", sent.path("systemInstruction").path("parts").get(0).path("text").asText());
    assertTrue(sent.path("generationConfig").has("responseJsonSchema"));
    assertEquals(2048, sent.path("generationConfig").path("maxOutputTokens").asInt());
    assertEquals(12L, reply.usage().promptTokens());
    assertNull(reply.usage().outputTokens());
    assertEquals(20L, reply.usage().totalTokens());
  }

  @Test
  void modelMetadataLimitRejectsBeforePaidPost() {
    metadata = metadata.replace("8192", "4799");
    assertEquals(
        "MODEL_LIMIT_EXCEEDED", assertThrows(GenerationFailure.class, this::generate).getMessage());
    assertEquals(0, posts.get());
  }

  @Test
  void errorBodiesAreDiscardedAndNotRetried() {
    for (int code : new int[] {401, 403, 429, 400, 404, 500, 503, 302}) {
      status = code;
      metadata = "fake-only-key credential and raw request";
      var error = assertThrows(GenerationFailure.class, this::generate);
      assertFalse(error.toString().contains("fake-only-key"));
      assertNull(error.getCause());
    }
    assertEquals(0, posts.get());
  }

  @Test
  void timeoutDoesNotRetryOrGenerate() {
    delay = 1500;
    assertEquals(
        "GENERATION_TIMEOUT", assertThrows(GenerationFailure.class, this::generate).getMessage());
    assertEquals(0, posts.get());
  }

  @Test
  void generationPostFailureIsNotRetried() {
    postStatus = 429;
    response = "fake-only-key provider-error";
    assertEquals(
        "GEMINI_RATE_LIMITED", assertThrows(GenerationFailure.class, this::generate).getMessage());
    assertEquals(1, posts.get());
  }

  @Test
  void outputLimitSafetyAndOversizeResponsesFailClosed() {
    response = response.replace("STOP", "MAX_TOKENS");
    assertEquals(
        "OUTPUT_LIMIT", assertThrows(GenerationFailure.class, this::generate).getMessage());
    response = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
    assertEquals(
        "GEMINI_BLOCKED", assertThrows(GenerationFailure.class, this::generate).getMessage());
    response = "x".repeat(1_048_577);
    assertThrows(GenerationFailure.class, this::generate);
    assertEquals(3, posts.get());
  }
}
