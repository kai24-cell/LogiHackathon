export interface SourceRange {
  beginLine: number;
  endLine: number;
}
export interface JavaMethod {
  methodId: string;
  declaringType: string;
  name: string;
  signature: string;
  constructor: boolean;
  range: SourceRange;
  body: string;
  annotations: string[];
  referencedTypes: string[];
}
export interface JavaType {
  typeId: string;
  qualifiedName: string;
  kind: string;
  range: SourceRange;
  roles: { name: string; evidence: string; confidence: number }[];
  fields: { name: string; type: string; range: SourceRange }[];
  methods: JavaMethod[];
}
export interface JavaAnalysis {
  snapshotId: string;
  files: {
    fileId: string;
    relativePath: string;
    parseStatus: "PARSED" | "FAILED";
    packageName: string;
    imports: string[];
    types: JavaType[];
    warnings: string[];
  }[];
  edges: {
    fromId: string;
    toId: string;
    kind: string;
    confidence: number;
    resolution: "RESOLVED" | "HEURISTIC";
    relativePath: string;
    line: number;
  }[];
  unresolved: {
    fromId: string;
    kind: string;
    reference: string;
    reason: string;
    relativePath: string;
    line: number;
  }[];
  warnings: string[];
}
