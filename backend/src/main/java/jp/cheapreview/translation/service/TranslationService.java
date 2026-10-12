package jp.cheapreview.translation.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.translation.dto.TranslationDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 取得済み英語回答の説明だけを翻訳し、失敗部分の原文を必ず保持する。 */
@Service
public class TranslationService {
  private final TranslationClient client;
  private final int maxCodePoints;
  private final Duration timeout;
  private final ExecutorService requests =
      Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());

  @Autowired
  public TranslationService(
      TranslationClient client,
      @Value("${cheapreview.translation.max-code-points:10000}") int maxCodePoints) {
    this(client, maxCodePoints, Duration.ofSeconds(30));
  }

  TranslationService(TranslationClient client, int maxCodePoints, Duration timeout) {
    if (maxCodePoints < 256 || maxCodePoints > 10000)
      throw new IllegalArgumentException("INVALID_TRANSLATION_LIMIT");
    this.client = client;
    this.maxCodePoints = maxCodePoints;
    this.timeout = timeout;
  }

  public TranslationDtos.Readiness readiness() {
    return new TranslationDtos.Readiness(
        client.configured(), client.configured() ? null : "TRANSLATION_NOT_READY");
  }

  private record Field(String path, ProtectedText text, Consumer<String> setter) {}

  /** JSONを再構築してcode/参照/severityを原文からコピーし、翻訳APIに送らない。 */
  public TranslationDtos.Result translate(
      GenerationDtos.Answer original, List<String> symbols, int attempt) {
    long started = System.nanoTime();
    long deadline = started + timeout.toNanos();
    List<TranslationDtos.Warning> warnings = new ArrayList<>();
    List<Field> fields = new ArrayList<>();
    String[] summary = {original.summary()};
    add(fields, "summary", original.summary(), symbols, value -> summary[0] = value);

    List<String[]> sections = sectionFields(original, symbols, fields);
    List<String[]> findings = findingFields(original, symbols, fields);
    List<String> limitations = new ArrayList<>(original.limitations());
    for (int index = 0; index < limitations.size(); index++) {
      int fixed = index;
      add(
          fields,
          "limitations[" + index + "]",
          limitations.get(index),
          symbols,
          value -> limitations.set(fixed, value));
    }

    if (!client.configured()) {
      for (Field field : fields)
        warnings.add(new TranslationDtos.Warning(field.path(), "TRANSLATION_NOT_READY"));
    } else {
      translateFields(fields, warnings, deadline);
    }

    var answer = display(original, summary[0], sections, findings, limitations);
    int failed = (int) warnings.stream().map(TranslationDtos.Warning::field).distinct().count();
    var status =
        warnings.isEmpty()
            ? TranslationDtos.Status.SUCCEEDED
            : failed == fields.size()
                ? TranslationDtos.Status.FAILED
                : TranslationDtos.Status.PARTIAL;
    return new TranslationDtos.Result(
        answer,
        status == TranslationDtos.Status.PARTIAL,
        List.copyOf(warnings),
        attempt,
        Duration.ofNanos(System.nanoTime() - started).toMillis(),
        status,
        warnings.isEmpty() ? null : warnings.getFirst().code());
  }

  /** セクションの説明だけを可変作業領域へ取り出し、コードは原文側に残す。 */
  private List<String[]> sectionFields(
      GenerationDtos.Answer original, List<String> symbols, List<Field> fields) {
    List<String[]> sections = new ArrayList<>();
    for (int index = 0; index < original.sections().size(); index++) {
      var section = original.sections().get(index);
      String[] texts = {section.title(), section.body()};
      sections.add(texts);
      add(fields, "sections[" + index + "].title", texts[0], symbols, value -> texts[0] = value);
      add(fields, "sections[" + index + "].body", texts[1], symbols, value -> texts[1] = value);
    }

    return sections;
  }

  private List<String[]> findingFields(
      GenerationDtos.Answer original, List<String> symbols, List<Field> fields) {
    List<String[]> findings = new ArrayList<>();
    for (int index = 0; index < original.findings().size(); index++) {
      var finding = original.findings().get(index);
      String[] texts = {finding.title(), finding.explanation(), finding.suggestion()};
      findings.add(texts);
      add(fields, "findings[" + index + "].title", texts[0], symbols, value -> texts[0] = value);
      add(
          fields,
          "findings[" + index + "].explanation",
          texts[1],
          symbols,
          value -> texts[1] = value);
      add(
          fields,
          "findings[" + index + "].suggestion",
          texts[2],
          symbols,
          value -> texts[2] = value);
    }

    return findings;
  }

  /** 翻訳済み説明を組み立て、参照・コード・severityを原文からそのまま引き継ぐ。 */
  private GenerationDtos.Answer display(
      GenerationDtos.Answer original,
      String summary,
      List<String[]> sections,
      List<String[]> findings,
      List<String> limitations) {
    List<GenerationDtos.Section> displaySections = new ArrayList<>();
    for (int index = 0; index < sections.size(); index++) {
      var raw = original.sections().get(index);
      var translated = sections.get(index);
      displaySections.add(
          new GenerationDtos.Section(translated[0], translated[1], raw.code(), raw.referenceIds()));
    }
    List<GenerationDtos.Finding> displayFindings = new ArrayList<>();
    for (int index = 0; index < findings.size(); index++) {
      var raw = original.findings().get(index);
      var translated = findings.get(index);
      displayFindings.add(
          new GenerationDtos.Finding(
              raw.severity(), translated[0], translated[1], translated[2], raw.referenceIds()));
    }
    return new GenerationDtos.Answer(
        summary,
        List.copyOf(displaySections),
        List.copyOf(displayFindings),
        List.copyOf(limitations));
  }

  private void add(
      List<Field> fields, String path, String raw, List<String> symbols, Consumer<String> setter) {
    if (raw != null && !raw.isBlank())
      fields.add(new Field(path, ProtectedText.protect(raw, symbols), setter));
  }

  /** 文・段落の境界でだけ分け、placeholderや識別子を単純文字切りしない。 */
  private void translateFields(
      List<Field> fields, List<TranslationDtos.Warning> warnings, long deadline) {
    List<Field> batch = new ArrayList<>();
    int total = 0;
    for (Field field : fields) {
      int count = points(field.text().value());
      if (count > maxCodePoints) {
        flush(batch, warnings, deadline);
        batch.clear();
        total = 0;
        translateLong(field, warnings, deadline);
        continue;
      }
      if (total + count > maxCodePoints || batch.size() == 1024) {
        flush(batch, warnings, deadline);
        batch.clear();
        total = 0;
      }
      batch.add(field);
      total += count;
    }
    flush(batch, warnings, deadline);
  }

  private void translateLong(Field field, List<TranslationDtos.Warning> warnings, long deadline) {
    // 分割不能な長い文は無理に切断せず、この文字列だけ英語を保持する。
    List<String> parts = split(field.text().value());
    if (parts.stream().anyMatch(part -> points(part) > maxCodePoints)) {
      warnings.add(new TranslationDtos.Warning(field.path(), "TRANSLATION_TEXT_TOO_LONG"));
      return;
    }
    StringBuilder combined = new StringBuilder();
    try {
      for (String part : parts) {
        var result = request(List.of(part), deadline);
        if (result.size() != 1) throw new TranslationFailure("TRANSLATION_INVALID_RESPONSE");
        combined.append(result.getFirst());
      }
      field.setter().accept(field.text().restore(combined.toString()));
    } catch (TranslationFailure failure) {
      warnings.add(new TranslationDtos.Warning(field.path(), failure.getMessage()));
    }
  }

  private List<String> split(String text) {
    List<String> parts = new ArrayList<>();
    var sentences =
        java.util.regex.Pattern.compile("(?<=[.!?\\n])(?=\\s)|(?<=\\n)").split(text, -1);
    StringBuilder part = new StringBuilder();
    for (String sentence : sentences) {
      if (points(part.toString()) + points(sentence) > maxCodePoints && !part.isEmpty()) {
        parts.add(part.toString());
        part.setLength(0);
      }
      part.append(sentence);
    }
    if (!part.isEmpty()) parts.add(part.toString());
    return parts;
  }

  private void flush(List<Field> batch, List<TranslationDtos.Warning> warnings, long deadline) {
    if (batch.isEmpty()) return;
    try {
      var translated =
          request(batch.stream().map(field -> field.text().value()).toList(), deadline);
      if (translated.size() != batch.size())
        throw new TranslationFailure("TRANSLATION_INVALID_RESPONSE");
      for (int index = 0; index < batch.size(); index++) {
        var field = batch.get(index);
        try {
          field.setter().accept(field.text().restore(translated.get(index)));
        } catch (TranslationFailure failure) {
          warnings.add(new TranslationDtos.Warning(field.path(), failure.getMessage()));
        }
      }
    } catch (TranslationFailure failure) {
      for (Field field : batch)
        warnings.add(new TranslationDtos.Warning(field.path(), failure.getMessage()));
    }
  }

  /** 全batch・認証・限定retryで共通の30秒枠を使い、期限後に次のbatchを送らない。 */
  private List<String> request(List<String> contents, long deadline) {
    if (Thread.currentThread().isInterrupted())
      throw new TranslationFailure("TRANSLATION_CANCELLED");
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new TranslationFailure("TRANSLATION_TIMEOUT");
    var task = requests.submit(() -> client.translate(contents, Duration.ofNanos(remaining)));
    try {
      return task.get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException ignored) {
      task.cancel(true);
      throw new TranslationFailure("TRANSLATION_TIMEOUT");
    } catch (InterruptedException ignored) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new TranslationFailure("TRANSLATION_CANCELLED");
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof TranslationFailure safe) throw safe;
      throw new TranslationFailure("TRANSLATION_FAILED");
    }
  }

  @PreDestroy
  public void close() {
    requests.shutdownNow();
  }

  private static int points(String value) {
    return value.codePointCount(0, value.length());
  }
}
