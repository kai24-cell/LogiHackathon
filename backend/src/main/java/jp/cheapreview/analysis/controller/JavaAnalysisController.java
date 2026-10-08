package jp.cheapreview.analysis.controller;

import jp.cheapreview.analysis.dto.JavaAnalysis;
import jp.cheapreview.analysis.service.JavaAnalysisService;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/snapshots/{snapshotId}")
public class JavaAnalysisController {
  private final WorkspaceService workspaces;
  private final JavaAnalysisService analysis;

  public JavaAnalysisController(WorkspaceService workspaces, JavaAnalysisService analysis) {
    this.workspaces = workspaces;
    this.analysis = analysis;
  }

  @GetMapping("/java-analysis")
  public JavaAnalysis.Result analyze(
      @PathVariable String workspaceId, @PathVariable String snapshotId) {
    return analysis.analyze(workspaces.getCapture(workspaceId, snapshotId));
  }
}
