export type SearchMode =
  | "SERVICE_REVIEW"
  | "CLASS_EXPLAIN"
  | "PROJECT_STRUCTURE"
  | "AUTH_ANALYSIS";

export interface SearchRequest {
  question: string;
  selectedFileIds: string[];
  mode: SearchMode;
}

export interface SearchScore {
  cosine: number;
  dependency: number;
  roleFit: number;
  cosineContribution: number;
  dependencyContribution: number;
  roleContribution: number;
  total: number;
}

export interface CodeSearchResult {
  snapshotId: string;
  mode: SearchMode;
  formulaVersion: string;
  weights: { cosine: number; dependency: number; roleFit: number };
  documentCount: number;
  warnings: string[];
  candidates: {
    rank: number;
    fileId: string;
    relativePath: string;
    primarySelected: boolean;
    dependencyDistance: number | null;
    bestMethodId: string | null;
    beginLine: number;
    score: SearchScore;
    matchingTerms: string[];
    roles: string[];
    reasons: string[];
  }[];
}
