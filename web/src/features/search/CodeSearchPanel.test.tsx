import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import {
  CodeSearchPanel,
  createSearchRequest,
  SearchResult,
} from "./CodeSearchPanel";
import type { CodeSearchResult } from "../../types/search";

function candidateResult(count: number): CodeSearchResult {
  return {
    snapshotId: "snapshot",
    mode: "SERVICE_REVIEW",
    formulaVersion: "test",
    documentCount: count,
    weights: { cosine: 0.5, dependency: 0.3, roleFit: 0.2 },
    warnings: [],
    candidates: Array.from({ length: count }, (_, index) => ({
      rank: index + 1,
      fileId: `file-${index}`,
      relativePath: `Candidate${index + 1}.java`,
      primarySelected: index === 0,
      dependencyDistance: 1,
      bestMethodId: `method-${index}`,
      beginLine: 10,
      roles: ["Service"],
      matchingTerms: [],
      reasons: [`選定理由${index + 1}`],
      score: {
        cosine: 0,
        dependency: 1,
        roleFit: 1,
        cosineContribution: 0,
        dependencyContribution: 0.3,
        roleContribution: 0.2,
        total: 0.5,
      },
    })),
  };
}

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
    expect(html).toContain("<details><summary>検索の仕組みと制限</summary>");
    expect(html).toContain('class="question-composer"');
    expect(html).toContain('class="question-input"');
  });

  it("上位3候補だけを既定表示し、残りの順位・理由・スコアを折り畳み内に保持する", () => {
    const result = candidateResult(5);
    const original = JSON.stringify(result);
    const html = renderToStaticMarkup(<SearchResult result={result} />);
    const tables = html.match(/<table>[\s\S]*?<\/table>/g) ?? [];

    expect(tables).toHaveLength(2);
    expect(tables[0]).toContain("Candidate3.java");
    expect(tables[0]).not.toContain("Candidate4.java");
    expect(html).toContain("その他の関連ファイル（2件）");
    expect(html).toContain(
      '<details open=""><summary>関連ファイル候補（5件）</summary>',
    );
    expect(html).toContain(
      "<details><summary>その他の関連ファイル（2件）</summary>",
    );
    expect(tables[1]).toContain("<td>4</td>");
    expect(tables[1]).toContain("<td>5</td>");
    expect(tables[1].indexOf("Candidate4.java")).toBeLessThan(
      tables[1].indexOf("Candidate5.java"),
    );
    expect(tables[1]).toContain("選定理由4");
    expect(tables[1]).toContain("0.5000");
    expect(JSON.stringify(result)).toBe(original);
  });

  it("3件以下は追加候補の折り畳みを表示しない", () => {
    const html = renderToStaticMarkup(
      <SearchResult result={candidateResult(3)} />,
    );
    expect(html).not.toContain("その他の関連ファイル（");
    expect(html.match(/<table>/g)).toHaveLength(1);
  });

  it("0件は候補なしを表示し、追加候補の折り畳みを表示しない", () => {
    const html = renderToStaticMarkup(
      <SearchResult result={candidateResult(0)} />,
    );
    expect(html).toContain("走査済みの検索候補がありません");
    expect(html).not.toContain("その他の関連ファイル（");
  });
});
