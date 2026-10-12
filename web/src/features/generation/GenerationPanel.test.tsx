import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  EnglishResult,
  GenerationPanel,
  generationError,
  sourceLabel,
  generationDiagnostics,
  conversationRefreshKey,
  TranslationSettingsStatus,
  translationError,
  TranslationWarnings,
  answerHeading,
  GenerationOperationFeedback,
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

it("refreshes committed history during translation and again when display changes", () => {
  const generating = {
    jobId: "j",
    state: "GENERATING",
    result: null,
  } as GenerationJob;
  expect(conversationRefreshKey(generating)).toBe("");
  const translating = {
    ...generating,
    state: "TRANSLATING",
    result: {},
  } as GenerationJob;
  expect(conversationRefreshKey(translating)).not.toBe("");
  expect(conversationRefreshKey({ ...translating })).toBe(
    conversationRefreshKey(translating),
  );
  const failed = {
    ...translating,
    state: "TRANSLATION_FAILED",
    translation: { attempt: 1, status: "FAILED" },
  } as GenerationJob;
  expect(conversationRefreshKey(failed)).not.toBe(
    conversationRefreshKey(translating),
  );
  const retrying = {
    ...failed,
    translation: { attempt: 2, status: "RUNNING" },
  } as GenerationJob;
  expect(conversationRefreshKey(retrying)).not.toBe(
    conversationRefreshKey(failed),
  );
});

it("shows the English submission wording when Japanese translation is not configured", () => {
  const html = renderToStaticMarkup(
    <TranslationSettingsStatus ready={false} />,
  );
  expect(html).toContain("現在は英語回答。日本語翻訳は未設定。");
  expect(html).toContain("会話履歴とTXT保存は英語回答でも利用できます。");
  expect(html).not.toContain("翻訳認証設定あり");
});

it("keeps a single authentication input mounted while hiding the inactive generation pane", () => {
  const html = renderToStaticMarkup(
    <GenerationPanel input={input} busy={false} activeSection="translation" />,
  );
  expect(html).toContain('data-pane="generate" hidden=""');
  expect(html).toContain('data-pane="translation"');
  expect(html.match(/type="password"/g)).toHaveLength(1);
});
it("groups repeated translation reasons while preserving every field detail", () => {
  const html = renderToStaticMarkup(
    <TranslationWarnings
      warnings={[
        { code: "TRANSLATION_NOT_READY", field: "summary" },
        { code: "TRANSLATION_NOT_READY", field: "sections[0].body" },
        { code: "TRANSLATION_TIMEOUT", field: "findings[0].explanation" },
      ]}
    />,
  );
  expect(html.match(/GCP認証とproject/g)).toHaveLength(1);
  expect(html).toContain("（2箇所）");
  expect(html).toContain("未翻訳箇所：summary");
  expect(html).toContain("未翻訳箇所：sections[0].body");
  expect(html).toContain("findings[0].explanation");
});
it("labels the original of a successfully translated answer as English original", () => {
  const translated = { status: "SUCCEEDED" } as NonNullable<
    GenerationJob["translation"]
  >;
  expect(answerHeading(true, translated)).toBe("英語原文");
  expect(answerHeading(false, translated)).toBe("日本語回答");
  expect(answerHeading(true, undefined)).toBe("英語回答（未翻訳）");
});

it("shows translation operation failures in the shared visible feedback pane", () => {
  for (const error of [
    "再翻訳を開始できません。接続を確認してください。（HTTP 409 / BUSY）",
    "キャンセルを確認できません。完了状態を確認してください。",
  ]) {
    const html = renderToStaticMarkup(
      <GenerationOperationFeedback activeSection="translation" error={error} />,
    );
    expect(html).toContain('role="alert"');
    expect(html).toContain(error);
    expect(html).not.toContain("hidden");
    expect(html.match(/role="alert"/g)).toHaveLength(1);
  }
});
