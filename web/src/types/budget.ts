import type { SearchMode } from "./search";

export interface BudgetRequest {
  workspaceId: string;
  snapshotId: string;
  question: string;
  mode: SearchMode;
  selectedFileIds: string[];
  inputBudgetTokens: number;
  modelId: string;
  targetFileId: string | null;
  targetMethodId: string | null;
  revision: number;
}

export interface CodeRange {
  beginLine: number;
  endLine: number;
}
export interface BudgetChunk {
  fileId: string;
  relativePath: string;
  mandatory: boolean;
  methodIds: string[];
  ranges: CodeRange[];
  omittedRanges: CodeRange[];
  code: string;
  reasons: string[];
}

export interface BudgetResult {
  previewId: string;
  snapshotId: string;
  revision: number;
  requestDigest: string;
  configVersion: string;
  expiresAt: string;
  counts: { ascii: number; japanese: number; other: number; tokens: number };
  mandatoryPromptTokens: number;
  relatedAdditionalTokens: number;
  historyTokens: number;
  historyTurnsIncluded: number;
  marginTokens: number;
  minimumBudgetTokens: number;
  mandatoryMinimumBudgetTokens: number;
  maximumOutputTokens: number;
  canExecute: boolean;
  selectedChunks: BudgetChunk[];
  excludedCandidates: {
    fileId: string;
    relativePath: string;
    reason: string;
  }[];
  warnings: string[];
  estimatedCost: {
    currency: string;
    inputPerMillion: number;
    outputPerMillion: number;
    checkedOn: string;
    inputCost: number;
    maximumOutputCost: number;
    total: number;
  } | null;
  promptMaterial: string;
  formula: {
    asciiWeight: number;
    japaneseWeight: number;
    otherWeight: number;
    marginRate: number;
    minimumMargin: number;
    version: string;
  };
}
