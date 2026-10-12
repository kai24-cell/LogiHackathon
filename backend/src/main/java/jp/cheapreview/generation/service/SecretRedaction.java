package jp.cheapreview.generation.service;

import java.util.Arrays;

/** JSONの成否に依存せず、保持前の文字列から認証キーの表現を除去する。 */
public final class SecretRedaction {
  private SecretRedaction() {}

  /** 不正JSONでも文字列を照合し、長いキーで正規表現の再帰が深くならないようにする。 */
  public static String redact(String raw, String key) {
    if (key == null || key.isEmpty()) return raw;

    // literalとescape混在の両方を元文字列の範囲へ戻し、重なる部分を一度に除去する。
    // 末尾backslashの半分だけを消したり、無関係なJSONの表現を変更したりしない。
    boolean[] hidden = new boolean[raw.length()];
    markMatches(raw, key, hidden, null);
    var decoded = decodeWithOffsets(raw);
    markMatches(decoded.text(), key, hidden, decoded);

    var result = new StringBuilder();
    for (int index = 0; index < raw.length(); ) {
      if (!hidden[index]) {
        result.append(raw.charAt(index++));
        continue;
      }

      result.append("[REDACTED]");
      while (index < hidden.length && hidden[index]) index++;
    }
    return result.toString();
  }

  private static void markMatches(String text, String key, boolean[] hidden, DecodedText decoded) {
    for (int index = text.indexOf(key);
        index >= 0;
        index = text.indexOf(key, index + key.length())) {
      int start = decoded == null ? index : decoded.starts()[index];
      int end = decoded == null ? index + key.length() : decoded.ends()[index + key.length() - 1];
      Arrays.fill(hidden, start, end, true);
    }
  }

  /** escapeを比較用にだけ復元し、元JSONを保持するため文字ごとの元範囲を記録する。 */
  private static DecodedText decodeWithOffsets(String raw) {
    var text = new StringBuilder();
    int[] starts = new int[raw.length()];
    int[] ends = new int[raw.length()];
    for (int index = 0; index < raw.length(); ) {
      int start = index;
      char character = raw.charAt(index++);
      if (character == '\\' && index < raw.length()) {
        char escaped = raw.charAt(index);
        int unicode = unicodeValue(raw, index);
        if (unicode >= 0) {
          character = (char) unicode;
          index += 5;
        } else if (escaped == '\\' || escaped == '"' || escaped == '/') {
          character = escaped;
          index++;
        }
      }

      starts[text.length()] = start;
      ends[text.length()] = index;
      text.append(character);
    }
    return new DecodedText(text.toString(), starts, ends);
  }

  private static int unicodeValue(String raw, int index) {
    if (raw.charAt(index) != 'u' || index + 5 > raw.length()) return -1;

    int value = 0;
    for (int digitIndex = index + 1; digitIndex < index + 5; digitIndex++) {
      int digit = Character.digit(raw.charAt(digitIndex), 16);
      if (digit < 0) return -1;
      value = value * 16 + digit;
    }
    return value;
  }

  private record DecodedText(String text, int[] starts, int[] ends) {
    @Override
    public String toString() {
      return "DecodedText[REDACTED]";
    }
  }
}
