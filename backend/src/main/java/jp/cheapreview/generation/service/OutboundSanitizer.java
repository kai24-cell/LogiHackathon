package jp.cheapreview.generation.service;

import java.util.ArrayList;
import java.util.regex.Pattern;
import jp.cheapreview.budget.dto.BudgetPreview.Chunk;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 固定除外に加え、保存済みJavaの典型的な認証リテラルをマスクする。万能な検出は保証しない。 */
public final class OutboundSanitizer {
  private static final String NAME =
      "(?:password|passwd|api[_-]?key|secret|access[_-]?token|auth[_-]?token|credential|private[_-]?key)";
  private static final Pattern ASSIGNMENT =
      Pattern.compile("(?i)(\\b[\\w]*" + NAME + "[\\w]*\\s*(?:=|:)\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");
  private static final Pattern MAP_VALUE =
      Pattern.compile("(?i)(\"[^\"]*" + NAME + "[^\"]*\"\\s*,\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");
  private static final Pattern RECOGNIZABLE =
      Pattern.compile(
          "AIza[\\w-]{30,}|AKIA[A-Z0-9]{16}|eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+|-----BEGIN [^-]*PRIVATE KEY-----|(?i:Bearer\\s+[\\w._-]{8,})");
  private static final Pattern UNRESOLVED =
      Pattern.compile("(?i)(?:" + NAME + ")[\\w]*\\s*(?:=|:)\\s*(?:\"(?!\\[REDACTED\\])|\"\"\"|')");

  private OutboundSanitizer() {}

  /** 原質問は改変しないため、疑わしい認証情報を含む場合は送信を止める。 */
  public static void checkQuestion(String question) {
    if (RECOGNIZABLE.matcher(question).find()
        || Pattern.compile("(?i)" + NAME + "\\s*(?:=|:)\\s*\\S+").matcher(question).find())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SECRET_IN_QUESTION");
  }

  /** 行番号と宣言を保持し、previewと外部送信が同じマスク済み材料になるようにする。 */
  public static Chunk sanitize(Chunk chunk) {
    if (Pattern.compile("(?i)" + NAME + "[\\w]*\\s*(?:=|:)\\s*\"\"\"").matcher(chunk.code()).find())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SECRET_REQUIRES_REVIEW");
    String code = ASSIGNMENT.matcher(chunk.code()).replaceAll("$1\"[REDACTED]\"");
    code = MAP_VALUE.matcher(code).replaceAll("$1\"[REDACTED]\"");
    // @Valueのデフォルト値も認証情報になり得る。placeholder全体を伏せ、値は保持しない。
    code =
        Pattern.compile("(?i)\"\\$\\{[^\"}]*" + NAME + "[^\"}]*:[^\"}]*}\\s*\"")
            .matcher(code)
            .replaceAll("\"[REDACTED]\"");
    if (Pattern.compile("(?im)^\\s*(?://|/\\*|\\*).*" + NAME + "\\s*[:=]\\s*\\S+")
        .matcher(code)
        .find())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SECRET_REQUIRES_REVIEW");
    // PEMやJWTなどは複数行／用途不明のため、部分置換せず明示的に送信を止める。
    if (RECOGNIZABLE.matcher(code).find() || UNRESOLVED.matcher(code).find())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SECRET_REQUIRES_REVIEW");
    if (code.equals(chunk.code())) return chunk;
    var reasons = new ArrayList<>(chunk.reasons());
    reasons.add("認証情報の可能性がある文字列リテラルを[REDACTED]へ置換。送信前にpreviewを確認してください。");
    return new Chunk(
        chunk.fileId(),
        chunk.relativePath(),
        chunk.mandatory(),
        chunk.methodIds(),
        chunk.ranges(),
        chunk.omittedRanges(),
        code,
        java.util.List.copyOf(reasons));
  }
}
