import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { AnalysisResult, analysisNodeLabels } from "./AnalysisResult";
import type { JavaAnalysis } from "../../types/analysis";

const result: JavaAnalysis = {
  snapshotId: "snapshot",
  files: [
    {
      fileId: "valid",
      relativePath: "src/注文.java",
      parseStatus: "PARSED",
      packageName: "demo",
      imports: [],
      warnings: [],
      types: [
        {
          typeId: "type",
          qualifiedName: "demo.Order",
          kind: "CLASS",
          range: { beginLine: 1, endLine: 4 },
          roles: [],
          fields: [],
          methods: [
            {
              methodId: "method",
              declaringType: "demo.Order",
              name: "save",
              signature: "save(int)",
              constructor: false,
              range: { beginLine: 2, endLine: 3 },
              body: "{}",
              annotations: [],
              referencedTypes: [],
            },
          ],
        },
      ],
    },
    {
      fileId: "broken",
      relativePath: "Broken.java",
      parseStatus: "FAILED",
      packageName: "",
      imports: [],
      types: [],
      warnings: ["PARSE_FAILED"],
    },
  ],
  edges: [
    {
      fromId: "method",
      toId: "type",
      kind: "TYPE_REFERENCE",
      confidence: 0.5,
      resolution: "HEURISTIC",
      relativePath: "src/注文.java",
      line: 2,
    },
  ],
  unresolved: [
    {
      fromId: "method",
      kind: "METHOD_CALL",
      reference: "external",
      reason: "UNRESOLVED_METHOD",
      relativePath: "src/注文.java",
      line: 3,
    },
  ],
  warnings: ["PARSE_FAILED: Broken.java"],
};

describe("Java analysis results", () => {
  it("renders successful files alongside failures and unresolved references", () => {
    const html = renderToStaticMarkup(<AnalysisResult result={result} />);
    expect(html).toContain("save(int)");
    expect(html).toContain("2–3行");
    expect(html).toContain("解析失敗");
    expect(html).toContain("他のファイルの解析は継続しました");
    expect(html).toContain("推定（一意候補）");
    expect(html).toContain("未解決の参照 1件");
    expect(html).toContain("src/注文.java:3");
  });

  it("uses type and overload signatures instead of opaque IDs", () => {
    expect(analysisNodeLabels(result).get("method")).toBe(
      "demo.Order.save(int)",
    );
    expect(analysisNodeLabels(result).get("type")).toBe("demo.Order");
  });
});
