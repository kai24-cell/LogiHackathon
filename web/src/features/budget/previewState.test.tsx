import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { BudgetSummary } from "./BudgetPanel";
import {
  currentResult,
  inputKey,
  PreviewRevisions,
  validInput,
  validationIsCurrent,
} from "./previewState";
import type { PreviewInput } from "./previewState";
import type { BudgetResult } from "../../types/budget";

const input: PreviewInput = {
  workspaceId: "w",
  snapshotId: "s",
  question: "保存",
  mode: "SERVICE_REVIEW",
  selectedFileIds: ["a"],
  inputBudgetTokens: 8192,
  modelId: "unknown",
  targetFileId: null,
  targetMethodId: null,
};
const result: BudgetResult = {
  previewId: "p",
  snapshotId: "s",
  revision: 1,
  requestDigest: "digest",
  configVersion: "c",
  expiresAt: "2026-10-10T00:05:00Z",
  counts: { ascii: 20, japanese: 4, other: 0, tokens: 4000 },
  mandatoryPromptTokens: 3500,
  relatedAdditionalTokens: 500,
  historyTokens: 0,
  historyTurnsIncluded: 0,
  marginTokens: 800,
  minimumBudgetTokens: 4800,
  mandatoryMinimumBudgetTokens: 4200,
  maximumOutputTokens: 2048,
  canExecute: false,
  selectedChunks: [],
  excludedCandidates: [],
  warnings: [],
  estimatedCost: null,
  promptMaterial: "material",
  formula: {
    asciiWeight: 0.35,
    japaneseWeight: 1.5,
    otherWeight: 1,
    marginRate: 0.2,
    minimumMargin: 512,
    version: "provisional",
  },
};

describe("budget preview state", () => {
  it("ignores old validation success and failure after a new preview for identical input", () => {
    const startedKey = inputKey(input);
    expect(validationIsCurrent(startedKey, "p", input, result)).toBe(true);
    const replacement = { ...result, previewId: "p2", revision: 2 };
    expect(validationIsCurrent(startedKey, "p", input, replacement)).toBe(
      false,
    );
    expect(validationIsCurrent(startedKey, "p", input, undefined)).toBe(false);
    expect(
      validationIsCurrent(
        startedKey,
        "p",
        { ...input, question: "別の質問" },
        result,
      ),
    ).toBe(false);
    expect(validationIsCurrent(startedKey, "p2", input, replacement)).toBe(
      true,
    );
  });
  it("rejects invalid integer budgets, blank questions and missing primary selections", () => {
    expect(validInput(input)).toBe(true);
    for (const amount of [1024, 100000])
      expect(validInput({ ...input, inputBudgetTokens: amount })).toBe(true);
    for (const amount of [-1, 0, 1023, 1024.5, Infinity, NaN, 100001])
      expect(validInput({ ...input, inputBudgetTokens: amount })).toBe(false);
    expect(validInput({ ...input, question: "　 \n" })).toBe(false);
    expect(validInput({ ...input, selectedFileIds: [] })).toBe(false);
    expect(validInput({ ...input, mode: "CLASS_EXPLAIN" })).toBe(false);
  });

  it("invalidates old result on every relevant input change and expiration", () => {
    const completed = { key: inputKey(input), result };
    const now = Date.parse("2026-10-10T00:00:00Z");
    expect(currentResult(completed, input, now)).toBe(result);
    for (const changed of [
      { ...input, question: "別の質問" },
      { ...input, selectedFileIds: ["b"] },
      { ...input, inputBudgetTokens: 9000 },
      { ...input, modelId: "different" },
      { ...input, snapshotId: "new" },
      { ...input, mode: "AUTH_ANALYSIS" as const },
      { ...input, targetMethodId: "method" },
    ])
      expect(currentResult(completed, changed, now)).toBeUndefined();
    expect(
      currentResult(completed, input, Date.parse(result.expiresAt)),
    ).toBeUndefined();
  });

  it("discards delayed responses including when inputs return to their previous values", () => {
    const revisions = new PreviewRevisions();
    const first = revisions.begin(input);
    expect(revisions.accepts(first, result, input)).toBe(true);
    const next = revisions.begin({ ...input, selectedFileIds: ["b"] });
    expect(revisions.accepts(first, result, input)).toBe(false);
    expect(
      revisions.accepts(
        next,
        { ...result, revision: next.revision },
        { ...input, selectedFileIds: ["b"] },
      ),
    ).toBe(true);
    const returned = revisions.begin(input);
    expect(revisions.accepts(first, result, input)).toBe(false);
    expect(
      revisions.accepts(
        returned,
        { ...result, revision: returned.revision },
        input,
      ),
    ).toBe(true);
  });

  it("displays safety margin, unknown price and keeps input/output separate", () => {
    const html = renderToStaticMarkup(<BudgetSummary result={result} />);
    expect(html).toContain("最低予算 4800");
    expect(html).toContain("安全余裕 800");
    expect(html).toContain("料金不明");
    expect(html).toContain("2048");
    expect(html).toContain("予算不足");
  });
});
