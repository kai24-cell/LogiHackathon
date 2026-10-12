package jp.cheapreview.translation.service;

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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CloudTranslationClientTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CloudTranslationClient.Authorization authorization =
      new CloudTranslationClient.Authorization() {
        public boolean configured() {
          return true;
        }

        public String token() {
          return "stub-token-not-real";
        }
      };

  @Test
  void sendsPlainEnglishArrayWithBearerHeaderAndMaintainsIndex() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v3/projects/fixture/locations/global:translateText",
        exchange -> {
          assertEquals(
              "Bearer stub-token-not-real", exchange.getRequestHeaders().getFirst("Authorization"));
          assertNull(exchange.getRequestURI().getQuery());
          var json = mapper.readTree(exchange.getRequestBody());
          assertEquals("en", json.path("sourceLanguageCode").asText());
          assertEquals("ja", json.path("targetLanguageCode").asText());
          assertEquals("text/plain", json.path("mimeType").asText());
          assertEquals("Flow", json.path("contents").get(0).asText());
          byte[] body =
              "{\"translations\":[{\"translatedText\":\"流れ\"},{\"translatedText\":\"説明\"}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try (var client = fixture(server, Duration.ofSeconds(3))) {
      assertEquals(List.of("流れ", "説明"), client.translate(List.of("Flow", "Explanation")));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void retries429And503OnlyOnceAndNeverRetriesOtherFailures() throws Exception {
    for (int status : List.of(429, 503, 400, 401, 403, 500)) {
      AtomicInteger calls = new AtomicInteger();
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            calls.incrementAndGet();
            byte[] body = "secret-error-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      try (var client = fixture(server, Duration.ofSeconds(3))) {
        var failure =
            assertThrows(TranslationFailure.class, () -> client.translate(List.of("Flow")));
        assertEquals(status, failure.httpStatus());
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
        assertEquals(status == 429 || status == 503 ? 2 : 1, calls.get());
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void responseTimeoutDoesNotRetry() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try {
            Thread.sleep(500);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.start();
    try (var client = fixture(server, Duration.ofMillis(100))) {
      assertEquals(
          "TRANSLATION_TIMEOUT",
          assertThrows(TranslationFailure.class, () -> client.translate(List.of("Flow")))
              .getMessage());
      assertTrue(calls.get() <= 1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readinessDoesNotLoadCredentialFileOrExploreMetadata() {
    assertFalse(CloudTranslationClient.localAuthorization(null, "fixture-project").configured());
    assertFalse(CloudTranslationClient.localAuthorization("not-read.json", "../bad").configured());
    assertTrue(
        CloudTranslationClient.localAuthorization("not-read.json", "fixture-project").configured());
    assertFalse(
        CloudTranslationClient.configuredAuthorization(
                true, "stub", "not-read.json", "fixture-project")
            .configured());
    assertFalse(
        CloudTranslationClient.configuredAuthorization(
                false, "gemini", "not-read.json", "fixture-project")
            .configured());
  }

  @Test
  void stoppedBodyAndOversizedResponseHaveSafeDistinctErrors() throws Exception {
    for (boolean oversized : List.of(false, true)) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try {
              if (oversized) {
                byte[] body = new byte[1024 * 1024 + 1];
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
              } else {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                Thread.sleep(700);
              }
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
              /* timeout/容量上限による切断は期待する。 */
            } finally {
              exchange.close();
            }
          });
      server.start();
      try (var client = fixture(server, Duration.ofMillis(300))) {
        assertEquals(
            oversized ? "TRANSLATION_INVALID_RESPONSE" : "TRANSLATION_TIMEOUT",
            assertThrows(TranslationFailure.class, () -> client.translate(List.of("Flow")))
                .getMessage());
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void authenticationIgnoringInterruptStillReturnsAtDeadlineWithoutSendingTranslation() {
    var slow =
        new CloudTranslationClient.Authorization() {
          public boolean configured() {
            return true;
          }

          public String token() {
            long deadline =
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(700);
            while (System.nanoTime() < deadline) {
              try {
                Thread.sleep(50);
              } catch (InterruptedException ignored) {
                /* SDKが割込みを無視する場合を再現。 */
              }
            }
            return "stub-token-not-real";
          }
        };
    var client =
        new CloudTranslationClient(
            mapper, slow, URI.create("http://127.0.0.1:1/"), Duration.ofMillis(100), 0);
    try {
      long started = System.nanoTime();
      for (int attempt = 0; attempt < 2; attempt++)
        assertEquals(
            "TRANSLATION_TIMEOUT",
            assertThrows(TranslationFailure.class, () -> client.translate(List.of("Flow")))
                .getMessage());
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1000);
    } finally {
      client.close();
    }
  }

  private Fixture fixture(HttpServer server, Duration timeout) {
    return new Fixture(
        new CloudTranslationClient(
            mapper,
            authorization,
            URI.create(
                "http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/v3/projects/fixture/locations/global:translateText"),
            timeout,
            0));
  }

  private record Fixture(CloudTranslationClient client) implements AutoCloseable {
    List<String> translate(List<String> contents) {
      return client.translate(contents);
    }

    public void close() {
      client.close();
    }
  }
}
