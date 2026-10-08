import { describe, expect, it } from "vitest";
import { formatScanWarning, scanOptionLabels } from "./scanPresentation";

describe("scan warning presentation", () => {
  it.each([
    "TOTAL_SIZE_LIMIT",
    "VISITED_ENTRY_LIMIT",
    "SOURCE_FILE_COUNT_LIMIT",
    "SCAN_TIMEOUT",
    "SCAN_CANCELLED",
  ])("%s explains that the scan is partial", (code) =>
    expect(formatScanWarning(code)).toContain("一部のみ走査されました"),
  );

  it("retains the complete detail, including colons and Japanese paths", () => {
    expect(formatScanWarning("FILE_SIZE_LIMIT: src/注文:Service.java")).toBe(
      "単一ファイルの容量上限を超えたため、このファイルを除外しました。 src/注文:Service.java",
    );
    expect(formatScanWarning("READ_FAILED: C:\\source\\Order.java")).toContain(
      "C:\\source\\Order.java",
    );
  });

  it("keeps unknown warnings visible", () => {
    expect(formatScanWarning("NEW_CODE: src/注文.java")).toBe(
      "不明な走査警告: NEW_CODE src/注文.java",
    );
    expect(formatScanWarning("UNKNOWN")).toBe("不明な走査警告: UNKNOWN");
    expect(formatScanWarning("toString")).toBe("不明な走査警告: toString");
  });
});

it("maps labels to option keys regardless of their enumeration order", () => {
  const keys = ["includeConfig", "includeTests", "includeGenerated"] as const;
  expect(keys.map((key) => scanOptionLabels[key])).toEqual([
    "application設定",
    "テスト",
    "生成Javaソース",
  ]);
});
