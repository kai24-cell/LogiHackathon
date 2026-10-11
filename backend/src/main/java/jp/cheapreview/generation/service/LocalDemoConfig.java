package jp.cheapreview.generation.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 明示指定されたローカル設定だけを読み、値や例外の本文を外部へ出さない。 */
@Component
public class LocalDemoConfig {
  private static final int MAX_BYTES = 8192;
  private final Path path;

  public LocalDemoConfig(@Value("${cheapreview.demo.config-path:}") String configuredPath) {
    path = configuredPath.isBlank() ? null : Path.of(configuredPath);
  }

  public boolean available() {
    return path != null && Files.isRegularFile(path);
  }

  public record Credentials(String modelId, String key) {
    @Override
    public String toString() {
      return "DemoCredentials[REDACTED]";
    }
  }

  /** 起動時や状態取得時にキーを読まず、利用者のデモ設定操作時だけ読み込む。 */
  public Credentials load() {
    if (!available()) throw failure("DEMO_NOT_CONFIGURED");
    try (var stream = Files.newInputStream(path)) {
      byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw failure("INVALID_DEMO_CONFIG");
      var values = new HashMap<String, String>();
      // dotenvをシェルとして実行しない。値の展開や認証ファイルの読取もしない。
      String text = new String(bytes, StandardCharsets.UTF_8);
      // VS CodeのUTF-8 BOM付き保存も許可する。キーや行内容は例外へ入れない。
      if (text.startsWith("\uFEFF")) text = text.substring(1);
      for (String line : text.split("\\R")) {
        if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
        int separator = line.indexOf('=');
        if (separator < 1) throw failure("INVALID_DEMO_CONFIG");
        String name = line.substring(0, separator).trim();
        if (values.putIfAbsent(name, unquote(line.substring(separator + 1).trim())) != null)
          throw failure("INVALID_DEMO_CONFIG");
      }
      String model = values.getOrDefault("CHEAPREVIEW_DEMO_MODEL", "");
      String key = values.getOrDefault("CHEAPREVIEW_DEMO_GEMINI_KEY", "");
      if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}")) throw failure("INVALID_DEMO_MODEL");
      if (!key.matches("[a-zA-Z0-9_-]{8,256}")) throw failure("INVALID_DEMO_KEY");
      // 翻訳用projectと認証ファイルのパスは予約項目。#8までは認証JSONを読まない。
      return new Credentials(model, key);
    } catch (IOException | IllegalArgumentException exception) {
      throw failure("INVALID_DEMO_CONFIG");
    }
  }

  private ResponseStatusException failure(String code) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
  }

  /** dotenvの外側の引用符だけを除去する。シェル展開・escape処理は行わない。 */
  private String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
