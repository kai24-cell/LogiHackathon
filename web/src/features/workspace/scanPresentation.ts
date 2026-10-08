import type { ScanOptions } from "../../types/workspace";

export const scanOptionLabels = {
  includeTests: "テスト",
  includeGenerated: "生成Javaソース",
  includeConfig: "application設定",
} satisfies Record<keyof ScanOptions, string>;

const warningMessages: Record<string, string> = {
  FILE_SIZE_LIMIT:
    "単一ファイルの容量上限を超えたため、このファイルを除外しました。",
  TOTAL_SIZE_LIMIT: "合計容量の上限に達したため、一部のみ走査されました。",
  VISITED_ENTRY_LIMIT:
    "ファイル・フォルダの訪問数上限に達したため、一部のみ走査されました。",
  SOURCE_FILE_COUNT_LIMIT:
    "対象ファイル数の上限に達したため、一部のみ走査されました。",
  SCAN_TIMEOUT: "走査時間の上限に達したため、一部のみ走査されました。",
  SCAN_CANCELLED: "走査を中断したため、一部のみ走査されました。",
  LINK_OR_SCOPE_SKIPPED: "リンクまたは許可範囲外のフォルダを除外しました。",
  LINK_SKIPPED: "リンクなどの通常ファイル以外の項目を除外しました。",
  FILE_CHANGED: "走査中に変更されたため、このファイルを除外しました。",
  READ_FAILED: "ファイルまたはフォルダを読み取れなかったため、除外しました。",
};

export function formatScanWarning(warning: string): string {
  const separator = warning.indexOf(":");
  const code = separator < 0 ? warning : warning.slice(0, separator);
  const message = Object.hasOwn(warningMessages, code)
    ? warningMessages[code]
    : `不明な走査警告: ${code}`;
  return separator < 0
    ? message
    : `${message} ${warning.slice(separator + 1).trimStart()}`;
}
