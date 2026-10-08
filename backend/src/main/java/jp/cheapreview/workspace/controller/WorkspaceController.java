package jp.cheapreview.workspace.controller;

import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.dto.WorkspaceDtos.ScanJob;
import jp.cheapreview.workspace.dto.WorkspaceDtos.ScanStarted;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Snapshot;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
  private final WorkspaceService workspaces;

  public WorkspaceController(WorkspaceService workspaces) {
    this.workspaces = workspaces;
  }

  @PostMapping("/workspaces/{id}/scan")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ScanStarted scan(@PathVariable String id, @RequestBody Options options) {
    return new ScanStarted(workspaces.startScan(id, options));
  }

  @GetMapping("/jobs/{id}")
  public ScanJob job(@PathVariable String id) {
    return workspaces.getJob(id);
  }

  @GetMapping("/workspaces/{id}/files")
  public Snapshot files(@PathVariable String id) {
    return workspaces.getSnapshot(id);
  }
}
