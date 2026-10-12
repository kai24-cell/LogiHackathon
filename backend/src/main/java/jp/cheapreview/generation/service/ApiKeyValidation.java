package jp.cheapreview.generation.service;

/** 認証文字列の形式を推測せず、安全にHTTPヘッダーへ渡せるかだけ確認する。 */
final class ApiKeyValidation {
  // キー仕様上の長さではなく、メモリとHTTPヘッダーの過大化を防ぐアプリの上限。
  private static final int MAX_HEADER_VALUE_LENGTH = 4096;

  private ApiKeyValidation() {}

  static boolean isSafeHeaderValue(String value) {
    // 接頭辞・最小桁数・記号の種類で認証の有効性を決めない。実APIに判断を委ねる。
    // 空白・制御文字・非ASCIIはヘッダーへの混入や送信例外を防ぐため拒否する。
    return value != null
        && !value.isEmpty()
        && value.length() <= MAX_HEADER_VALUE_LENGTH
        && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
  }
}
