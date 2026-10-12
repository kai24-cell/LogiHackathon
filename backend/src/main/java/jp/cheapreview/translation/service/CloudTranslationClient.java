package jp.cheapreview.translation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.ServiceAccountCredentials;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jp.cheapreview.generation.service.SecretRedaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cloud Translation Advanced v3へ説明文字列だけを送る。URL・本文・認証値は記録しない。 */
@Component
public class CloudTranslationClient implements TranslationClient {
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

  interface Authorization {
    boolean configured();

    String token();
  }

  private final ObjectMapper mapper;
  private final Authorization authorization;
  private final URI endpoint;
  private final Duration timeout;
  private final long backoffMs;
  private final HttpClient http;
  private final ExecutorService authentication =
      Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).factory());

  @Autowired
  public CloudTranslationClient(
      ObjectMapper mapper,
      @Value("${cheapreview.translation.enabled:true}") boolean enabled,
      @Value("${cheapreview.generation.provider:gemini}") String provider) {
    this(
        mapper,
        configuredAuthorization(
            enabled,
            provider,
            System.getenv("GOOGLE_APPLICATION_CREDENTIALS"),
            System.getenv("CHEAPREVIEW_GCP_PROJECT")),
        productionEndpoint(System.getenv("CHEAPREVIEW_GCP_PROJECT")),
        Duration.ofSeconds(30),
        1000);
  }

  CloudTranslationClient(
      ObjectMapper mapper,
      Authorization authorization,
      URI endpoint,
      Duration timeout,
      long backoffMs) {
    this.mapper = mapper;
    this.authorization = authorization;
    this.endpoint = endpoint;
    this.timeout = timeout;
    this.backoffMs = backoffMs;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  private static boolean validProject(String project) {
    return project != null && project.matches("(?:[a-z][a-z0-9-]{4,61}[a-z0-9]|[0-9]{1,20})");
  }

  /** 固定providerを選んだ場合は親環境に実認証があっても翻訳を無効にする。 */
  static Authorization configuredAuthorization(
      boolean enabled, String provider, String credentialPath, String project) {
    return localAuthorization(enabled && !"stub".equals(provider) ? credentialPath : null, project);
  }

  private static URI productionEndpoint(String project) {
    String fixed = validProject(project) ? project : "unconfigured";
    return URI.create(
        "https://translate.googleapis.com/v3/projects/"
            + fixed
            + "/locations/global:translateText");
  }

  /** 明示されたservice-accountだけを実送信時に読む。起動時のADC/metadata探索を行わない。 */
  static Authorization localAuthorization(String credentialPath, String project) {
    return new Authorization() {
      public boolean configured() {
        return credentialPath != null && !credentialPath.isBlank() && validProject(project);
      }

      public String token() {
        if (!configured()) throw new TranslationFailure("TRANSLATION_NOT_READY");
        try (var stream = Files.newInputStream(Path.of(credentialPath))) {
          // OAuth自動retryを無効化し、Translationの429/503限定retryと混同しない。
          var credentials =
              ServiceAccountCredentials.fromStream(stream).toBuilder()
                  .setScopes(List.of("https://www.googleapis.com/auth/cloud-translation"))
                  .setDefaultRetriesEnabled(false)
                  .build();
          return credentials.refreshAccessToken().getTokenValue();
        } catch (Exception ignored) {
          throw new TranslationFailure("TRANSLATION_AUTH_FAILED");
        }
      }
    };
  }

  public boolean configured() {
    return authorization.configured();
  }

  /** 認証取得を含め1リクエスト30秒以内。429/503だけ1秒待って最大1回再試行する。 */
  public List<String> translate(List<String> contents) {
    return translate(contents, timeout);
  }

  @Override
  public List<String> translate(List<String> contents, Duration remainingBudget) {
    if (!configured()) throw new TranslationFailure("TRANSLATION_NOT_READY");
    if (contents.isEmpty()
        || contents.size() > 1024
        || contents.stream().anyMatch(java.util.Objects::isNull)
        || contents.stream().mapToLong(value -> value.codePointCount(0, value.length())).sum()
            > 10000) throw new TranslationFailure("TRANSLATION_TEXT_TOO_LONG");
    long expires = System.nanoTime() + Math.min(timeout.toNanos(), remainingBudget.toNanos());
    var tokenTask = authentication.submit(authorization::token);
    String token;
    try {
      token = tokenTask.get(remaining(expires), TimeUnit.NANOSECONDS);
    } catch (TimeoutException ignored) {
      tokenTask.cancel(true);
      throw new TranslationFailure("TRANSLATION_TIMEOUT");
    } catch (InterruptedException ignored) {
      tokenTask.cancel(true);
      Thread.currentThread().interrupt();
      throw new TranslationFailure("TRANSLATION_CANCELLED");
    } catch (ExecutionException ignored) {
      throw new TranslationFailure("TRANSLATION_AUTH_FAILED");
    }
    if (token == null || !token.matches("[\\x21-\\x7e]{1,8192}"))
      throw new TranslationFailure("TRANSLATION_AUTH_FAILED");

    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        return send(contents, token, expires);
      } catch (TranslationFailure failure) {
        if (attempt != 0
            || (failure.httpStatus() == null
                || (failure.httpStatus() != 429 && failure.httpStatus() != 503))) throw failure;
        try {
          TimeUnit.MILLISECONDS.sleep(
              Math.min(backoffMs, TimeUnit.NANOSECONDS.toMillis(remaining(expires))));
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
          throw new TranslationFailure("TRANSLATION_CANCELLED");
        }
      }
    }
    throw new TranslationFailure("TRANSLATION_FAILED");
  }

  private static long remaining(long expires) {
    long left = expires - System.nanoTime();
    if (left <= 0) throw new TranslationFailure("TRANSLATION_TIMEOUT");
    return left;
  }

  private List<String> send(List<String> contents, String token, long expires) {
    CompletableFuture<HttpResponse<String>> response = null;
    try {
      var body = mapper.createObjectNode();
      body.put("mimeType", "text/plain")
          .put("sourceLanguageCode", "en")
          .put("targetLanguageCode", "ja");
      var array = body.putArray("contents");
      contents.forEach(array::add);
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofNanos(remaining(expires)))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
              .build();
      response = http.sendAsync(request, info -> new LimitedBody());
      var reply = response.get(remaining(expires), TimeUnit.NANOSECONDS);
      int status = reply.statusCode();
      if (status < 200 || status >= 300) {
        String code =
            status == 401 || status == 403
                ? "TRANSLATION_AUTH_FAILED"
                : status == 429 ? "TRANSLATION_QUOTA_EXCEEDED" : "TRANSLATION_FAILED";
        throw new TranslationFailure(code, status);
      }
      var translations =
          mapper.readTree(SecretRedaction.redact(reply.body(), token)).path("translations");
      if (!translations.isArray() || translations.size() != contents.size())
        throw new TranslationFailure("TRANSLATION_INVALID_RESPONSE");
      List<String> result = new ArrayList<>();
      for (var item : translations) {
        var text = item.path("translatedText");
        if (!text.isTextual()) throw new TranslationFailure("TRANSLATION_INVALID_RESPONSE");
        result.add(text.asText());
      }
      return List.copyOf(result);
    } catch (TranslationFailure failure) {
      throw failure;
    } catch (TimeoutException ignored) {
      throw new TranslationFailure("TRANSLATION_TIMEOUT");
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      throw new TranslationFailure("TRANSLATION_CANCELLED");
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof TranslationFailure safe) throw safe;
      if (failure.getCause() instanceof java.net.http.HttpTimeoutException)
        throw new TranslationFailure("TRANSLATION_TIMEOUT");
      throw new TranslationFailure("TRANSLATION_FAILED");
    } catch (Exception ignored) {
      throw new TranslationFailure("TRANSLATION_FAILED");
    } finally {
      if (response != null && !response.isDone()) response.cancel(true);
    }
  }

  @PreDestroy
  public void close() {
    authentication.shutdownNow();
  }

  /** 翻訳・エラーの巨大本文を上限付きで受け取り、本文を例外へ渡さない。 */
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
          completed.completeExceptionally(new TranslationFailure("TRANSLATION_INVALID_RESPONSE"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    public void onError(Throwable ignored) {
      completed.completeExceptionally(
          new TranslationFailure(
              ignored instanceof java.net.http.HttpTimeoutException
                  ? "TRANSLATION_TIMEOUT"
                  : "TRANSLATION_FAILED"));
    }

    public void onComplete() {
      completed.complete(bytes.toString(StandardCharsets.UTF_8));
    }
  }
}
