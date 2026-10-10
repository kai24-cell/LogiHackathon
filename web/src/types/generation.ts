import type { PreviewInput } from "../features/budget/previewState";
import type { BudgetResult } from "./budget";

export interface GenerationSettings {
  modelId: string;
  configured: boolean;
  version: string;
}
export interface GenerationStart {
  previewId: string;
  requestId: string;
  settingsVersion: string;
  input: PreviewInput & { revision: number };
}
export interface EnglishAnswer {
  summary: string;
  sections: {
    title: string;
    body: string;
    code: string;
    referenceIds: string[];
  }[];
  findings: {
    severity: "INFO" | "LOW" | "MEDIUM" | "HIGH";
    title: string;
    explanation: string;
    suggestion: string;
    referenceIds: string[];
  }[];
  limitations: string[];
}
export interface GenerationJob {
  jobId: string;
  state: "QUEUED" | "GENERATING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  errorCode: string | null;
  result: {
    answerEnglish: EnglishAnswer;
    references: {
      referenceId: string;
      fileId: string;
      relativePath: string;
      ranges: { beginLine: number; endLine: number }[];
    }[];
    warnings: string[];
    usage: {
      promptTokens: number | null;
      outputTokens: number | null;
      thoughtTokens: number | null;
      cachedTokens: number | null;
      totalTokens: number | null;
    };
    estimatedPromptTokens: number;
    generationMs: number;
    finishReason: string;
    modelId: string;
    snapshotId: string;
    question: string;
    mode: PreviewInput["mode"];
    workspaceId: string;
  } | null;
}
export function canGenerate(
  input: PreviewInput,
  preview: BudgetResult | undefined,
  settings: GenerationSettings | undefined,
  running: boolean,
  busy: boolean,
): boolean {
  return (
    !busy &&
    !running &&
    !!preview?.canExecute &&
    !!settings?.configured &&
    settings.modelId === input.modelId &&
    Date.now() < Date.parse(preview.expiresAt)
  );
}
