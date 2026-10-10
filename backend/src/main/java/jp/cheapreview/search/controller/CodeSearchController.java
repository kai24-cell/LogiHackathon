package jp.cheapreview.search.controller;

import jp.cheapreview.search.dto.CodeSearch;
import jp.cheapreview.search.service.CodeSearchService;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/snapshots/{snapshotId}")
public class CodeSearchController {
  private final WorkspaceService workspaces;
  private final CodeSearchService search;

  public CodeSearchController(WorkspaceService workspaces, CodeSearchService search) {
    this.workspaces = workspaces;
    this.search = search;
  }

  @PostMapping("/code-search")
  public CodeSearch.Result search(
      @PathVariable String workspaceId,
      @PathVariable String snapshotId,
      @RequestBody CodeSearch.Request request) {
    return search.search(workspaceId, workspaces.getCapture(workspaceId, snapshotId), request);
  }
}
