package jp.cheapreview.translation.service;

import java.util.List;

public interface TranslationClient {
  boolean configured();

  List<String> translate(List<String> contents);

  default List<String> translate(List<String> contents, java.time.Duration remaining) {
    return translate(contents);
  }
}
