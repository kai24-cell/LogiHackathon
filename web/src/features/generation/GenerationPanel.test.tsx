import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  EnglishResult,
  GenerationPanel,
  generationError,
} from "./GenerationPanel";
import { canGenerate, type GenerationJob } from "../../types/generation";
import type { PreviewInput } from "../budget/previewState";
import type { BudgetResult } from "../../types/budget";

const input: PreviewInput = {
  workspaceId: "w",
  snapshotId: "s",
  question: "説明して",
  mode: "SERVICE_REVIEW",
  selectedFileIds: ["f"],
  inputBudgetTokens: 8192,
  modelId: "fixture-model",
  targetFileId: null,
  targetMethodId: null,
};
const settings = { modelId: "fixture-model", configured: true, version: "v" };
const preview = {
  canExecute: true,
  expiresAt: "2026-10-10T00:05:00Z",
} as BudgetResult;
afterEach(() => vi.useRealTimers());
describe("Gemini generation controls", () => {
  it("requires configured matching model, current affordable preview and idle state", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-10T00:00:00Z"));
    expect(canGenerate(input, preview, settings, false, false)).toBe(true);
    expect(canGenerate(input, preview, settings, true, false)).toBe(false);
    expect(canGenerate(input, preview, settings, false, true)).toBe(false);
    expect(canGenerate(input, undefined, settings, false, false)).toBe(false);
    expect(
      canGenerate(
        input,
        { ...preview, canExecute: false },
        settings,
        false,
        false,
      ),
    ).toBe(false);
    expect(
      canGenerate(
        input,
        preview,
        { ...settings, configured: false },
        false,
        false,
      ),
    ).toBe(false);
    expect(
      canGenerate(
        input,
        preview,
        { ...settings, modelId: "different" },
        false,
        false,
      ),
    ).toBe(false);
    vi.setSystemTime(new Date(preview.expiresAt));
    expect(canGenerate(input, preview, settings, false, false)).toBe(false);
  });
  it("does not echo unknown provider errors or sensitive raw messages", () => {
    expect(generationError("raw-body-api-key-sentinel")).not.toContain(
      "sentinel",
    );
    expect(generationError("GEMINI_AUTH_FAILED")).toContain("APIキー");
    expect(generationError("GENERATION_TIMEOUT")).toContain(
      "自動再試行はしません",
    );
    expect(generationError("FILE_CHANGED")).toContain("再走査");
  });
  it("shows explicit real API cost notice and empty password input with no storage", () => {
    const html = renderToStaticMarkup(
      <GenerationPanel input={input} busy={false} />,
    );
    expect(html).toContain("料金が発生する可能性");
    expect(html).toContain('type="password"');
    expect(html).toContain("キー未設定");
    expect(html).toContain("今回は英語の構造化回答");
  });
  it("escapes answer code and uses trusted reference ranges with nullable API usage", () => {
    const result: NonNullable<GenerationJob["result"]> = {
      answerEnglish: {
        summary: "<script>fake</script>",
        sections: [
          {
            title: "Flow",
            body: "Explanation",
            code: "<img onerror=alert(1)>",
            referenceIds: ["ref-1", "unknown"],
          },
        ],
        findings: [],
        limitations: ["Static only"],
      },
      references: [
        {
          referenceId: "ref-1",
          fileId: "f",
          relativePath: "Service.java",
          ranges: [{ beginLine: 2, endLine: 5 }],
        },
      ],
      warnings: [],
      usage: {
        promptTokens: 12,
        outputTokens: null,
        thoughtTokens: null,
        cachedTokens: null,
        totalTokens: 12,
      },
      estimatedPromptTokens: 20,
      generationMs: 10,
      finishReason: "STOP",
      modelId: "fixture-model",
      snapshotId: "s",
      question: "説明して",
      mode: "SERVICE_REVIEW",
      workspaceId: "w",
    };
    const html = renderToStaticMarkup(<EnglishResult result={result} />);
    expect(html).not.toContain("<script>");
    expect(html).not.toContain("<img");
    expect(html).toContain("Service.java：2–5行");
    expect(html).not.toContain("unknown");
    expect(html).toContain("未提供");
    expect(html).toContain("入力推定 20");
  });
});
