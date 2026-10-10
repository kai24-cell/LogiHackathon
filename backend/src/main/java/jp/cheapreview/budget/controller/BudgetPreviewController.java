package jp.cheapreview.budget.controller;

import jp.cheapreview.budget.dto.BudgetPreview.Request;
import jp.cheapreview.budget.dto.BudgetPreview.Result;
import jp.cheapreview.budget.service.BudgetPreviewService;
import jp.cheapreview.workspace.service.WorkspaceCapture;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/analysis/preview")
public class BudgetPreviewController {
  private final WorkspaceService workspaces;
  private final BudgetPreviewService previews;

  public BudgetPreviewController(WorkspaceService workspaces, BudgetPreviewService previews) {
    this.workspaces = workspaces;
    this.previews = previews;
  }

  @PostMapping
  public Result preview(@RequestBody Request request) {
    return previews.preview(capture(request), request);
  }

  @PostMapping("/{previewId}/validate")
  public Result validate(@PathVariable String previewId, @RequestBody Request request) {
    return previews.check(previewId, capture(request), request);
  }

  private WorkspaceCapture capture(Request request) {
    if (request == null
        || request.workspaceId() == null
        || request.workspaceId().isBlank()
        || request.snapshotId() == null
        || request.snapshotId().isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PREVIEW_REQUEST");
    return workspaces.getCapture(request.workspaceId(), request.snapshotId());
  }
}
