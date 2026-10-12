package jp.cheapreview.translation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 説明中のコード、送信済みsymbol、相対パスを翻訳対象から外す。 */
final class ProtectedText {
  private static final Pattern INLINE = Pattern.compile("```[\\s\\S]*?```|`[^`\\r\\n]+`");
  private static final Pattern TOKEN = Pattern.compile("__CR_KEEP_[0-9]+__");
  private final String original;
  private final String protectedText;
  private final Map<String, String> replacements;

  private ProtectedText(String original, String protectedText, Map<String, String> replacements) {
    this.original = original;
    this.protectedText = protectedText;
    this.replacements = replacements;
  }

  /** 長いsymbolから照合し、短い名前が型名やパスの途中を先に置換することを防ぐ。 */
  static ProtectedText protect(String original, List<String> symbols) {
    List<String> candidates = new ArrayList<>(symbols);
    INLINE.matcher(original).results().forEach(match -> candidates.add(match.group()));
    // 原文にplaceholder形式があっても衝突・偽装として解釈しないよう同じ辞書へ退避する。
    TOKEN.matcher(original).results().forEach(match -> candidates.add(match.group()));
    var ordered =
        candidates.stream()
            .filter(value -> value != null && !value.isEmpty())
            .distinct()
            .sorted(
                Comparator.comparingInt(String::length).reversed().thenComparing(value -> value))
            .toList();
    Map<String, String> replacements = new LinkedHashMap<>();
    StringBuilder output = new StringBuilder();
    for (int cursor = 0; cursor < original.length(); ) {
      String selected = null;
      for (String candidate : ordered) {
        if (original.startsWith(candidate, cursor) && boundary(original, cursor, candidate)) {
          selected = candidate;
          break;
        }
      }
      if (selected == null) {
        output.append(original.charAt(cursor++));
        continue;
      }
      // 原文中に存在しないIDを選び、復元時の文字列再置換も行わない。
      int index = replacements.size();
      String token;
      do {
        token = "__CR_KEEP_" + String.format(java.util.Locale.ROOT, "%04d", index++) + "__";
      } while (original.contains(token) || replacements.containsKey(token));
      replacements.put(token, selected);
      output.append(token);
      cursor += selected.length();
    }
    return new ProtectedText(original, output.toString(), replacements);
  }

  private static boolean boundary(String text, int offset, String symbol) {
    if (Character.isJavaIdentifierPart(symbol.charAt(0))
        && offset > 0
        && Character.isJavaIdentifierPart(text.charAt(offset - 1))) return false;
    int end = offset + symbol.length();
    return !Character.isJavaIdentifierPart(symbol.charAt(symbol.length() - 1))
        || end == text.length()
        || !Character.isJavaIdentifierPart(text.charAt(end));
  }

  String value() {
    return protectedText;
  }

  String original() {
    return original;
  }

  /** 欠落・重複・未知のIDは文字列全体を不採用にし、英語原文へ戻す。 */
  String restore(String translated) {
    if (translated == null || (translated.isBlank() && !original.isBlank()))
      throw new TranslationFailure("TRANSLATION_INVALID_RESPONSE");
    Map<String, Integer> seen = new LinkedHashMap<>();
    var matcher = TOKEN.matcher(translated);
    while (matcher.find()) seen.merge(matcher.group(), 1, Integer::sum);
    if (!seen.keySet().equals(replacements.keySet())
        || seen.values().stream().anyMatch(count -> count != 1))
      throw new TranslationFailure("TRANSLATION_PLACEHOLDER_MISMATCH");
    StringBuilder restored = new StringBuilder();
    matcher.reset();
    int previous = 0;
    while (matcher.find()) {
      restored
          .append(translated, previous, matcher.start())
          .append(replacements.get(matcher.group()));
      previous = matcher.end();
    }
    return restored.append(translated, previous, translated.length()).toString();
  }
}
