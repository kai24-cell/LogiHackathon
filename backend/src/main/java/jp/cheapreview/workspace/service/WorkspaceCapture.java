package jp.cheapreview.workspace.service;

import java.nio.file.Path;
import java.util.Map;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Snapshot;

/**
 * Backend-only immutable source snapshot. Never serialize root or source text as a file listing.
 */
public record WorkspaceCapture(Path root, Snapshot snapshot, Map<String, String> javaSources) {
  public WorkspaceCapture {
    javaSources = Map.copyOf(javaSources);
  }
}
