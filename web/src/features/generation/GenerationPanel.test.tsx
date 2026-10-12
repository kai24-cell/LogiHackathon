import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  EnglishResult,
  GenerationPanel,
  generationError,
  sourceLabel,
  generationDiagnostics,
  translationError,
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
  it("explains model and quota errors without echoing unknown diagnostics", () => {
    expect(generationError("MODEL_NOT_FOUND")).toContain("廃止");
    expect(generationError("MODEL_NOT_FOUND")).toContain("断定できません");
    expect(generationError("GEMINI_QUOTA_EXCEEDED")).toContain("利用枠");
    const text = generationDiagnostics({
      httpStatus: 429,
      apiStatus: "RESOURCE_EXHAUSTED",
      stage: "GENERATE",
      limitType: "PER_DAY",
    });
    expect(text).toContain("HTTP 429");
    const timeout = generationDiagnostics({
      httpStatus: null,
      apiStatus: "UNKNOWN",
      stage: "JOB",
      limitType: "UNKNOWN",
      timeoutType: "JOB_DEADLINE",
      elapsedMs: 240000,
    });
    expect(timeout).toContain("ジョブ期限超過");
    expect(timeout).toContain("経過 240000 ms");
    expect(timeout).toContain("HTTP 未特定");
    expect(text).toContain("日単位");
    expect(text).toContain("回答生成");
    expect(
      generationDiagnostics({
        httpStatus: 404,
        apiStatus: "raw-secret-sentinel",
        stage: "MODEL_INFO",
        limitType: "UNKNOWN",
      }),
    ).not.toContain("sentinel");
  });
  it("distinguishes fixed responses from paid real API authentication", () => {
    expect(sourceLabel("STUB")).toContain("固定応答");
    expect(sourceLabel("STUB")).toContain("外部API呼出なし");
    expect(sourceLabel("DEMO")).toContain("実API");
    expect(sourceLabel("DEMO")).toContain("料金");
  });
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
    expect(html).toContain("キー設定状態を確認できません");
    expect(html).not.toContain("キー未設定");
    // 失敗後も既存キーが残る可能性があるため、状態不明でも明示解除は可能にする。
    expect(html).toContain("<button>設定解除</button>");
    expect(html).toContain("翻訳できない部分は英語原文");
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
      source: "STUB",
    };
    const html = renderToStaticMarkup(<EnglishResult result={result} />);
    expect(html).not.toContain("<script>");
    expect(html).not.toContain("<img");
    expect(html).toContain("Service.java：2–5行");
    expect(html).not.toContain("unknown");
    expect(html).toContain("未提供");
    expect(html).toContain("入力推定 20");
    expect(html).toContain("固定応答");
    const translated = renderToStaticMarkup(
      <EnglishResult
        result={result}
        translation={{
          displayAnswer: { ...result.answerEnglish, summary: "日本語の説明" },
          partialTranslation: true,
          warnings: [
            {
              field: "sections[0].body",
              code: "TRANSLATION_PLACEHOLDER_MISMATCH",
            },
          ],
          attempt: 2,
          durationMs: 10,
          status: "PARTIAL",
          errorCode: "TRANSLATION_PLACEHOLDER_MISMATCH",
        }}
      />,
    );
    expect(translated).toContain("日本語の説明");
    expect(translated).toContain("一部は英語原文");
    expect(translated).toContain("英語原文を表示");
    expect(translated).toContain("未翻訳箇所：sections[0].body");
    expect(translated).toContain("Service.java：2–5行");
    expect(translated).not.toContain("<img");
  });
  it("explains translation failure without exposing arbitrary provider text", () => {
    expect(translationError("TRANSLATION_NOT_READY")).toContain("GCP認証");
    expect(translationError("secret-sentinel")).not.toContain(
      "secret-sentinel",
    );
    expect(translationError("TRANSLATION_TIMEOUT")).toContain("英語原文");
  });
});
