package jp.cheapreview.analysis.service;

import com.github.javaparser.ast.Node;
import java.util.ArrayList;
import java.util.List;

/** Maps JavaParser's 1-based inclusive positions onto unchanged source text. */
final class SourceText {
  private final String source;
  private final List<Integer> lineStarts = new ArrayList<>(List.of(0));

  SourceText(String source) {
    this.source = source;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '\r' || character == '\n') {
        if (character == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') {
          index++;
        }
        lineStarts.add(index + 1);
      }
    }
  }

  String slice(Node node) {
    var range = node.getRange().orElseThrow();
    int begin = lineStarts.get(range.begin.line - 1) + range.begin.column - 1;
    int end = lineStarts.get(range.end.line - 1) + range.end.column;
    return source.substring(begin, end);
  }
}
