package jp.cheapreview.generation.service;

import java.util.regex.Pattern;

/** JSONの成否に依存せず、保持前の文字列から認証キーの表現を除去する。 */
final class SecretRedaction {
  private SecretRedaction() {}

  /** 通常文字とJSON Unicodeエスケープの混在も、不正JSONのまま照合する。 */
  static String redact(String raw, String key) {
    if (key == null || key.isEmpty()) return raw;

    // キーは設定でASCIIに限定される。文字ごとに両表現を許可し、schema検証失敗時も残さない。
    // 通常文字の大文字小文字は区別し、エスケープの16進数字だけは大小両方を受け入れる。
    var expression = new StringBuilder();
    for (char character : key.toCharArray()) {
      expression
          .append("(?:")
          .append(Pattern.quote(String.valueOf(character)))
          .append("|\\\\u(?i:")
          .append(String.format("%04x", (int) character))
          .append("))");
    }

    return Pattern.compile(expression.toString()).matcher(raw).replaceAll("[REDACTED]");
  }
}
