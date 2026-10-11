package jp.cheapreview.generation.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jp.cheapreview.budget.dto.BudgetPreview;
import jp.cheapreview.budget.service.PromptMaterializer;
import jp.cheapreview.generation.dto.GenerationDtos;
import org.springframework.stereotype.Component;

/** 提供者のschema対応だけを信用せず、型・必須項目・未知項目と送信参照を検証する。 */
@Component
public class AnswerValidator {
  private final ObjectMapper mapper;

  public AnswerValidator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public record Validated(
      GenerationDtos.Answer answer,
      List<GenerationDtos.Reference> references,
      List<String> warnings) {}

  public Validated validate(String raw, BudgetPreview.Result preview, BudgetPreview.Request input) {
    return validate(raw, preview, input, null);
  }

  /** JSONのUnicodeエスケープを復元した値でも、APIキーを回答・履歴へ残さない。 */
  public Validated validate(
      String raw, BudgetPreview.Result preview, BudgetPreview.Request input, String secret) {
    try {
      var strict =
          mapper
              .copy()
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .enable(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
      var root = strict.readTree(raw);
      validateNode(
          root,
          strict.readTree(PromptMaterializer.bundle(input, preview.selectedChunks()).schema()));
      if (secret != null) redact(root, secret);
      var references = new ArrayList<GenerationDtos.Reference>();
      for (int i = 0; i < preview.selectedChunks().size(); i++) {
        var chunk = preview.selectedChunks().get(i);
        if (chunk.ranges().isEmpty()
            || chunk.ranges().stream()
                .anyMatch(range -> range.beginLine() < 1 || range.endLine() < range.beginLine()))
          throw new GenerationFailure("INVALID_ANSWER");
        references.add(
            new GenerationDtos.Reference(
                "ref-" + (i + 1), chunk.fileId(), chunk.relativePath(), chunk.ranges()));
      }
      var allowed = new HashSet<String>();
      references.forEach(reference -> allowed.add(reference.referenceId()));
      var warnings = new ArrayList<>(preview.warnings());
      // モデルにファイルや行番号を生成させず、送信した参照IDだけに結び付ける。
      for (String field : List.of("sections", "findings"))
        for (var item : root.path(field)) {
          var kept = strict.createArrayNode();
          for (var ref : item.path("referenceIds")) {
            if (allowed.contains(ref.asText())) kept.add(ref.asText());
            else if (!warnings.contains("不正な回答参照を非表示にしました。")) warnings.add("不正な回答参照を非表示にしました。");
          }
          ((com.fasterxml.jackson.databind.node.ObjectNode) item).set("referenceIds", kept);
        }
      return new Validated(
          strict.treeToValue(root, GenerationDtos.Answer.class),
          List.copyOf(references),
          List.copyOf(warnings));
    } catch (Exception exception) {
      throw new GenerationFailure("INVALID_ANSWER");
    }
  }

  private void redact(JsonNode node, String secret) {
    if (node.isObject()) {
      var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
      object
          .properties()
          .forEach(
              entry -> {
                if (entry.getValue().isTextual())
                  object.put(
                      entry.getKey(), entry.getValue().asText().replace(secret, "[REDACTED]"));
                else redact(entry.getValue(), secret);
              });
    } else if (node.isArray()) {
      var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        if (array.get(i).isTextual())
          array.set(
              i,
              com.fasterxml.jackson.databind.node.TextNode.valueOf(
                  array.get(i).asText().replace(secret, "[REDACTED]")));
        else redact(array.get(i), secret);
      }
    }
  }

  /** schemaを単一の契約として再帰検証し、null・未知フィールド・非文字列参照を拒否する。 */
  private void validateNode(JsonNode node, JsonNode schema) {
    if (node == null || node.isNull()) throw new GenerationFailure("INVALID_ANSWER");
    if (schema.has("enum")) {
      boolean match = false;
      for (var value : schema.path("enum")) if (value.equals(node)) match = true;
      if (!match) throw new GenerationFailure("INVALID_ANSWER");
    }
    switch (schema.path("type").asText()) {
      case "object" -> {
        if (!node.isObject()) throw new GenerationFailure("INVALID_ANSWER");
        Set<String> expected = new HashSet<>();
        schema.path("required").forEach(value -> expected.add(value.asText()));
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new GenerationFailure("INVALID_ANSWER");
        node.fields()
            .forEachRemaining(
                entry ->
                    validateNode(entry.getValue(), schema.path("properties").path(entry.getKey())));
      }
      case "array" -> {
        if (!node.isArray()) throw new GenerationFailure("INVALID_ANSWER");
        node.forEach(item -> validateNode(item, schema.path("items")));
      }
      case "string" -> {
        if (!node.isTextual()) throw new GenerationFailure("INVALID_ANSWER");
      }
      default -> {
        if (!schema.has("enum")) throw new GenerationFailure("INVALID_ANSWER");
      }
    }
  }
}
