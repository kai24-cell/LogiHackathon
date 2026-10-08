export interface Status {
  bridgeConnected: boolean;
  windowId: string;
  demoAvailable: boolean;
  translationReady: boolean;
}
export interface SourceFile {
  fileId: string;
  relativePath: string;
  language: string;
  sha256: string;
  sizeBytes: number;
}
export interface Snapshot {
  snapshotId: string;
  files: SourceFile[];
  warnings: string[];
}
export interface BridgeRequest {
  requestId: string;
  status: "pending" | "claimed" | "completed" | "cancelled" | "failed";
  result: { workspaceId?: string; name?: string } | null;
}
