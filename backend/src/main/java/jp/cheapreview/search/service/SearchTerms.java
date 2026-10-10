package jp.cheapreview.search.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 英字識別子と日本語原文を特徴語に変換する。翻訳や日英辞書は使わない。 */
final class SearchTerms {
  private static final Pattern WORDS =
      Pattern.compile("[A-Za-z0-9_]+|[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー]+");

  private SearchTerms() {}

  /** 原識別子を残しつつcamel/snake caseを分割し、日本語をcode point単位の2gramにする。 */
  static Map<String, Integer> counts(String text) {
    Map<String, Integer> counts = new TreeMap<>();
    var matcher = WORDS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
    while (matcher.find()) {
      String word = matcher.group();
      if (word.matches("[A-Za-z0-9_]+")) {
        add(counts, word.toLowerCase(Locale.ROOT));
        var parts =
            List.of(
                word.replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                    .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                    .split("[_ ]+"));
        if (parts.size() > 1) {
          parts.stream()
              .filter(part -> !part.isEmpty())
              .forEach(part -> add(counts, part.toLowerCase(Locale.ROOT)));
        }
      } else {
        int[] points = word.codePoints().toArray();
        if (points.length == 1) add(counts, word);
        for (int i = 0; i + 1 < points.length; i++) {
          add(counts, new String(points, i, 2));
        }
      }
    }

    return counts;
  }

  private static void add(Map<String, Integer> counts, String term) {
    counts.merge(term, 1, Integer::sum);
  }
}
