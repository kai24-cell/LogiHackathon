package jp.cheapreview.workspace.service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import jp.cheapreview.bridge.dto.BridgeDtos.FolderResult;
import jp.cheapreview.workspace.dto.WorkspaceDtos.JobState;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.dto.WorkspaceDtos.ScanJob;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {
  private final WorkspaceScanner scanner;
  private final int maxWorkspaces;
  private final int maxJobs;
  private final ExecutorService executor;
  private final Map<String, Path> roots = new HashMap<>();
  private final Map<String, Snapshot> snapshots = new HashMap<>();
  private final Map<String, ScanJob> jobs = new LinkedHashMap<>();
  private String activeJobId;

  public WorkspaceService(
      WorkspaceScanner scanner,
      @Value("${cheapreview.workspace.max-workspaces:20}") int maxWorkspaces,
      @Value("${cheapreview.workspace.max-jobs:100}") int maxJobs,
      @Value("${cheapreview.workspace.worker-threads:2}") int workerThreads) {
    if (maxWorkspaces < 1 || maxJobs < 1 || workerThreads < 1) {
      throw new IllegalArgumentException("Invalid workspace limits");
    }
    this.scanner = scanner;
    this.maxWorkspaces = maxWorkspaces;
    this.maxJobs = maxJobs;
    executor = Executors.newFixedThreadPool(workerThreads);
  }

  public synchronized FolderResult register(String uri) {
    Path root;
    try {
      root = WorkspacePaths.validateRoot(uri);
    } catch (IOException | IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE");
    }
    if (roots.size() >= maxWorkspaces) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "WORKSPACE_LIMIT");
    }
    String workspaceId = UUID.randomUUID().toString();
    roots.put(workspaceId, root);
    String name = root.getFileName() == null ? "選択フォルダ" : root.getFileName().toString();
    return new FolderResult(workspaceId, name);
  }

  public synchronized String startScan(String workspaceId, Options options) {
    Path root = roots.get(workspaceId);
    if (root == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }
    if (activeJobId != null) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "BUSY");
    }
    if (jobs.size() >= maxJobs) {
      jobs.remove(jobs.keySet().iterator().next());
    }
    String jobId = UUID.randomUUID().toString();
    activeJobId = jobId;
    jobs.put(jobId, new ScanJob(jobId, JobState.QUEUED, null));
    try {
      executor.submit(() -> runScan(jobId, workspaceId, root, options));
    } catch (RejectedExecutionException exception) {
      activeJobId = null;
      jobs.put(jobId, new ScanJob(jobId, JobState.FAILED, "SCAN_UNAVAILABLE"));
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SCAN_UNAVAILABLE");
    }
    return jobId;
  }

  public synchronized ScanJob getJob(String jobId) {
    ScanJob job = jobs.get(jobId);
    if (job == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }
    return job;
  }

  public synchronized Snapshot getSnapshot(String workspaceId) {
    Snapshot snapshot = snapshots.get(workspaceId);
    if (snapshot == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_SCANNED");
    }
    return snapshot;
  }

  private void runScan(String jobId, String workspaceId, Path root, Options options) {
    synchronized (this) {
      jobs.put(jobId, new ScanJob(jobId, JobState.SCANNING, null));
    }
    Snapshot snapshot = null;
    ScanJob completedJob;
    try {
      snapshot = scanner.scan(root, options);
      completedJob = new ScanJob(jobId, JobState.SUCCEEDED, null);
    } catch (Exception exception) {
      completedJob = new ScanJob(jobId, JobState.FAILED, "SCAN_FAILED");
    }
    // Snapshot publication, terminal status and admission release are one transition.
    synchronized (this) {
      if (snapshot != null) {
        snapshots.put(workspaceId, snapshot);
      }
      jobs.put(jobId, completedJob);
      activeJobId = null;
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
