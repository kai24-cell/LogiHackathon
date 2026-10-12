import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { BudgetPanel, BudgetSummary } from "./BudgetPanel";
import type { BudgetResult } from "../../types/budget";

it("shows the model ID as text while keeping the API key masked", () => {
  const html = renderToStaticMarkup(
    <BudgetPanel
      workspaceId="w"
      snapshotId="s"
      question="説明して"
      mode="SERVICE_REVIEW"
      selected={new Set(["f"])}
      busy={false}
    />,
  );
  expect(html).toContain(
    'type="text" autoComplete="off" value="unconfigured-model"',
  );
  expect(html).toContain('type="password" autoComplete="off" value=""');
  expect(html).toContain("入力トークン予算（tokens）");
  expect(html).toContain("キー設定状態を確認できません");
});

it("displays USD estimate, separate input and maximum output costs and thinking limitation", () => {
  const result: BudgetResult = {
    previewId: "p",
    snapshotId: "s",
    revision: 1,
    requestDigest: "d",
    configVersion: "v",
    expiresAt: "2026-10-12T15:00:00Z",
    counts: { ascii: 0, japanese: 0, other: 0, tokens: 1041 },
    mandatoryPromptTokens: 883,
    relatedAdditionalTokens: 158,
    historyTokens: 0,
    historyTurnsIncluded: 0,
    marginTokens: 512,
    minimumBudgetTokens: 1553,
    mandatoryMinimumBudgetTokens: 1395,
    maximumOutputTokens: 2048,
    canExecute: true,
    selectedChunks: [],
    excludedCandidates: [],
    warnings: ["思考・cache・翻訳料金は含めていません。"],
    estimatedCost: {
      currency: "USD",
      inputPerMillion: 0.75,
      outputPerMillion: 3.75,
      checkedOn: "2026-10-12",
      inputCost: 0.000781,
      maximumOutputCost: 0.00768,
      total: 0.008461,
    },
    promptMaterial: "",
    formula: {
      asciiWeight: 0.35,
      japaneseWeight: 1.5,
      otherWeight: 1,
      marginRate: 0.2,
      minimumMargin: 512,
      version: "v1",
    },
  };

  const html = renderToStaticMarkup(<BudgetSummary result={result} />);
  expect(html).toContain(
    "概算費用 0.008461 USD（入力 0.000781 ＋ 最大出力 0.007680）",
  );
  expect(html).toContain("入力 0.75、出力 3.75。確認日 2026-10-12");
  expect(html).toContain("思考・cache・翻訳料金は含めていません。");
  expect(html).not.toContain("料金不明");
});

it("keeps handoff and generation mounted when displaying only the budget pane", () => {
  const html = renderToStaticMarkup(
    <BudgetPanel
      workspaceId="w"
      snapshotId="s"
      question="説明して"
      mode="SERVICE_REVIEW"
      selected={new Set(["f"])}
      busy={false}
      activeSection="budget"
    />,
  );
  expect(html).toContain('data-pane="handoff" hidden=""');
  expect(html).toContain('data-pane="generate" hidden=""');
  expect(html).toContain("入力トークン予算（tokens）");
  expect(html.match(/type="password"/g)).toHaveLength(1);
});

it("folds additional selected chunks and excluded candidates without dropping their details", () => {
  const result = {
    selectedChunks: Array.from({ length: 4 }, (_, index) => ({
      fileId: String(index),
      relativePath: `File${index}.java`,
      mandatory: index === 0,
      reasons: ["選定理由"],
      methodIds: ["method"],
      omittedRanges: [{ beginLine: 8, endLine: 9 }],
      code: `code-${index}`,
    })),
    excludedCandidates: [
      { fileId: "excluded", relativePath: "Excluded.java", reason: "予算上限" },
    ],
    warnings: [],
    counts: { tokens: 123 },
    formula: {},
    historyTurnsIncluded: 0,
  } as unknown as BudgetResult;
  const html = renderToStaticMarkup(<BudgetSummary result={result} />);
  expect(html).toContain("残りの選定コード 1件");
  expect(html).toContain("File3.java");
  expect(html).toContain("code-3");
  expect(html).toContain("8–9");
  expect(html).toContain("除外候補 1件と理由");
  expect(html).toContain("Excluded.java: 予算上限");
});
