package jp.cheapreview.workspace.dto;

import java.util.List;

public final class WorkspaceDtos {
  private WorkspaceDtos() {}

  public record Options(boolean includeTests, boolean includeGenerated, boolean includeConfig) {}

  public record SourceFile(
      String fileId, String relativePath, String language, String sha256, long sizeBytes) {}

  public record Snapshot(String snapshotId, List<SourceFile> files, List<String> warnings) {}

  public enum JobState {
    QUEUED,
    SCANNING,
    SUCCEEDED,
    FAILED
  }

  public record ScanStarted(String scanJobId) {}

  public record ScanJob(String jobId, JobState state, String errorCode) {}
}
