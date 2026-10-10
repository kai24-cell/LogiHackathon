import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import {
  CodeSearchPanel,
  createSearchRequest,
  SearchResult,
} from "./CodeSearchPanel";
import type { CodeSearchResult } from "../../types/search";

describe("関連コード検索", () => {
  it("質問の原文を保持し、主選択の順序を固定して空白質問を拒否する", () => {
    expect(
      createSearchRequest(" \n　", new Set(), "SERVICE_REVIEW"),
    ).toBeUndefined();
    const selected = new Set(["b", "a"]);
    expect(
      createSearchRequest(" 注文を保存したい ", selected, "SERVICE_REVIEW"),
    ).toEqual({
      question: " 注文を保存したい ",
      selectedFileIds: ["a", "b"],
      mode: "SERVICE_REVIEW",
    });
    expect([...selected]).toEqual(["b", "a"]);
  });

  it("順位・理由・各寄与・総合スコア・一致なし警告を表示する", () => {
    const result: CodeSearchResult = {
      snapshotId: "snapshot",
      mode: "SERVICE_REVIEW",
      formulaVersion: "test",
      documentCount: 2,
      weights: { cosine: 0.5, dependency: 0.3, roleFit: 0.2 },
      warnings: ["質問とコードの共通語がありません"],
      candidates: [
        {
          rank: 1,
          fileId: "a",
          relativePath: "OrderService.java",
          primarySelected: true,
          dependencyDistance: 0,
          bestMethodId: "m",
          beginLine: 5,
          roles: ["Service"],
          matchingTerms: [],
          reasons: ["ユーザー主選択", "主選択からの最短依存距離: 0hop"],
          score: {
            cosine: 0,
            dependency: 1,
            roleFit: 1,
            cosineContribution: 0,
            dependencyContribution: 0.3,
            roleContribution: 0.2,
            total: 0.5,
          },
        },
      ],
    };
    const html = renderToStaticMarkup(<SearchResult result={result} />);
    for (const value of [
      "OrderService.java",
      "主選択",
      "0hop",
      "0.3000",
      "0.2000",
      "0.5000",
      "共通語がありません",
      "スコア内訳",
    ]) {
      expect(html).toContain(value);
    }
  });

  it("空の質問では検索ボタンを無効化し、検索の限界を表示する", () => {
    const html = renderToStaticMarkup(
      <CodeSearchPanel
        selected={new Set()}
        busy={false}
        onSearch={async () => {
          throw new Error("unused");
        }}
      />,
    );
    expect(html).toContain("disabled");
    expect(html).toContain("翻訳せず検索");
  });
});
