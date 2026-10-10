package jp.cheapreview.search.service;

import java.util.Set;
import jp.cheapreview.search.dto.CodeSearch.Mode;

/** 設計書9.4の役割表を適用する。質問の語からモードを推測しない。 */
final class RoleFit {
  private RoleFit() {}

  static double score(
      Mode mode, SearchIndex.Document document, boolean selected, Integer distance) {
    Set<String> roles = document.roles();
    return switch (mode) {
      case SERVICE_REVIEW ->
          roles.contains("Service")
              ? 1
              : roles.stream()
                      .anyMatch(
                          role ->
                              Set.of("Repository", "Entity", "Controller", "DTO").contains(role))
                  ? 0.5
                  : 0;
      // ファイル主選択をtargetとし、2hop以内に解析済みの型があるファイルを関連型とする暫定判断。
      case CLASS_EXPLAIN ->
          selected
              ? 1
              : distance != null
                      && document.analysis() != null
                      && !document.analysis().types().isEmpty()
                  ? 0.5
                  : 0;
      case PROJECT_STRUCTURE ->
          !roles.isEmpty()
              ? 1
              : document.analysis() != null && !document.analysis().types().isEmpty() ? 0.5 : 0;
      // 認証設定はSecurity根拠、ユーザー関連Serviceはuser/account識別子を根拠にする。辞書翻訳はしない。
      case AUTH_ANALYSIS ->
          roles.contains("Security")
              ? 1
              : roles.contains("Controller")
                      || (roles.contains("Service") && document.userRelated())
                  ? 0.5
                  : 0;
    };
  }
}
