package jp.cheapreview.translation.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import jp.cheapreview.budget.dto.BudgetPreview;

/** 今回送信したコードだけから保護辞書を作り、ディスクや走査対象外を読み直さない。 */
public final class TranslationSymbols {
  private TranslationSymbols() {}

  public static List<String> from(List<BudgetPreview.Chunk> chunks) {
    var parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    var symbols = new LinkedHashSet<String>();
    for (int index = 0; index < chunks.size(); index++) {
      var chunk = chunks.get(index);
      symbols.add(chunk.relativePath());
      symbols.add(chunk.fileId());
      symbols.add("ref-" + (index + 1));
      var parsed = parser.parse(chunk.code());
      parsed
          .getResult()
          .ifPresent(
              unit -> {
                unit.findAll(SimpleName.class).forEach(name -> symbols.add(name.asString()));
                unit.findAll(Name.class).forEach(name -> symbols.add(name.asString()));
              });
      // 送信チャンクにはクラス外の省略署名も含まれるため、完全ASTだけでは名前を網羅できない。
      // コメント・文字列内の任意語を除いた字句走査で、送信済み識別子だけを補う。
      collectIdentifiers(chunk.code(), symbols);
    }
    return List.copyOf(symbols);
  }

  private static void collectIdentifiers(String source, Set<String> symbols) {
    for (int offset = 0; offset < source.length(); ) {
      if (source.startsWith("//", offset)) {
        int newline = source.indexOf('\n', offset + 2);
        offset = newline < 0 ? source.length() : newline + 1;
      } else if (source.startsWith("/*", offset)) {
        int close = source.indexOf("*/", offset + 2);
        offset = close < 0 ? source.length() : close + 2;
      } else if (source.startsWith("\"\"\"", offset)) {
        offset = quoted(source, offset + 3, "\"\"\"");
      } else if (source.charAt(offset) == '"' || source.charAt(offset) == '\'') {
        offset = quoted(source, offset + 1, String.valueOf(source.charAt(offset)));
      } else {
        int point = source.codePointAt(offset);
        if (!Character.isJavaIdentifierStart(point)) {
          offset += Character.charCount(point);
          continue;
        }
        int start = offset;
        offset += Character.charCount(point);
        while (offset < source.length()
            && Character.isJavaIdentifierPart(source.codePointAt(offset)))
          offset += Character.charCount(source.codePointAt(offset));
        String token = source.substring(start, offset);
        if (!SourceVersion.isKeyword(token, SourceVersion.RELEASE_21)) symbols.add(token);
      }
    }
  }

  /** 通常文字列・文字リテラル・text blockを読み飛ばし、引用内の語をsymbolへ格上げしない。 */
  private static int quoted(String source, int offset, String delimiter) {
    while (offset < source.length()) {
      if (source.charAt(offset) == '\\') {
        offset = Math.min(source.length(), offset + 2);
        continue;
      }
      if (source.startsWith(delimiter, offset)) return offset + delimiter.length();
      offset++;
    }
    return source.length();
  }
}
