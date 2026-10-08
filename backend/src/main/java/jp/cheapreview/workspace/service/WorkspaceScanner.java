package jp.cheapreview.workspace.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Snapshot;
import jp.cheapreview.workspace.dto.WorkspaceDtos.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceScanner {
  private static final Set<String> EXCLUDED_DIRECTORIES =
      Set.of(".git", ".svn", "node_modules", "target", "build", "dist", ".tools", ".cache");
  private static final String APPLICATION_CONFIG_PATTERN =
      "application(?:-[a-z0-9_-]+)?\\.(yml|yaml|properties)";
  private final long maxFileBytes;
  private final int maxFiles;
  private final long maxTotalBytes;
  private final Duration timeout;

  public WorkspaceScanner(
      @Value("${cheapreview.scan.max-file-bytes}") long maxFileBytes,
      @Value("${cheapreview.scan.max-files}") int maxFiles,
      @Value("${cheapreview.scan.max-total-bytes}") long maxTotalBytes,
      @Value("${cheapreview.scan.timeout-seconds}") long timeoutSeconds) {
    if (maxFileBytes <= 0
        || maxFileBytes >= Integer.MAX_VALUE
        || maxFiles <= 0
        || maxTotalBytes <= 0
        || timeoutSeconds <= 0) {
      throw new IllegalArgumentException("Invalid scan limits");
    }
    this.maxFileBytes = maxFileBytes;
    this.maxFiles = maxFiles;
    this.maxTotalBytes = maxTotalBytes;
    timeout = Duration.ofSeconds(timeoutSeconds);
  }

  public Snapshot scan(Path root, Options options) throws IOException {
    WorkspacePaths.requireWithinRoot(root, root);
    ScanTraversal traversal = new ScanTraversal(root, options);
    Files.walkFileTree(root, traversal);
    return traversal.snapshot();
  }

  private boolean excludeDirectory(String name, Options options) {
    return EXCLUDED_DIRECTORIES.contains(name)
        || isSecretName(name)
        || (!options.includeTests() && name.equals("test"))
        || (!options.includeGenerated() && name.contains("generated"));
  }

  private boolean eligibleFile(String name, Options options) {
    if (isSecretName(name)) {
      return false;
    }
    if (!options.includeTests() && name.endsWith("test.java")) {
      return false;
    }
    return name.endsWith(".java")
        || (options.includeConfig() && name.matches(APPLICATION_CONFIG_PATTERN));
  }

  private boolean isSecretName(String name) {
    return name.startsWith(".env")
        || name.contains("credential")
        || name.contains("secret")
        || name.contains("service-account")
        || name.equals("runtime.json")
        || name.endsWith(".pem")
        || name.endsWith(".key");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  // All traversal state belongs to one scan and is never shared across worker threads.
  private final class ScanTraversal extends SimpleFileVisitor<Path> {
    private final Path root;
    private final Options options;
    private final List<SourceFile> files = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final long startedNanos = System.nanoTime();
    private long totalBytes;
    private int visitedEntries;

    private ScanTraversal(Path root, Options options) {
      this.root = root;
      this.options = options;
    }

    private boolean shouldStop() {
      if (Thread.currentThread().isInterrupted()) {
        warnings.add("SCAN_CANCELLED");
        return true;
      }
      if (System.nanoTime() - startedNanos > timeout.toNanos()) {
        warnings.add("SCAN_TIMEOUT");
        return true;
      }
      // Count directories as well, so a tree of empty folders cannot bypass the limit.
      if (++visitedEntries > maxFiles) {
        warnings.add("FILE_COUNT_LIMIT");
        return true;
      }
      return false;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
      if (!directory.equals(root) && excludeDirectory(fileName(directory), options)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      if (shouldStop()) {
        return FileVisitResult.TERMINATE;
      }
      try {
        WorkspacePaths.requireWithinRoot(directory, root);
      } catch (IOException exception) {
        warnings.add("LINK_OR_SCOPE_SKIPPED");
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
      if (shouldStop()) {
        return FileVisitResult.TERMINATE;
      }
      if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
        warnings.add("LINK_SKIPPED");
        return FileVisitResult.CONTINUE;
      }
      String name = fileName(path);
      if (!eligibleFile(name, options)) {
        return FileVisitResult.CONTINUE;
      }
      String relativePath = root.relativize(path).toString().replace('\\', '/');
      if (attributes.size() > maxFileBytes) {
        warnings.add("FILE_SIZE_LIMIT: " + relativePath);
        return FileVisitResult.CONTINUE;
      }
      if (attributes.size() > maxTotalBytes - totalBytes) {
        warnings.add("TOTAL_SIZE_LIMIT");
        return FileVisitResult.TERMINATE;
      }
      readSource(path, relativePath, attributes);
      return FileVisitResult.CONTINUE;
    }

    private void readSource(Path path, String relativePath, BasicFileAttributes beforeRead) {
      try {
        WorkspacePaths.requireWithinRoot(path, root);
        byte[] bytes = readBounded(path, beforeRead.size());
        BasicFileAttributes afterRead =
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (bytes.length != beforeRead.size()
            || !beforeRead.lastModifiedTime().equals(afterRead.lastModifiedTime())
            || !java.util.Objects.equals(beforeRead.fileKey(), afterRead.fileKey())) {
          warnings.add("FILE_CHANGED: " + relativePath);
          return;
        }
        WorkspacePaths.requireWithinRoot(path, root);
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
        totalBytes += bytes.length;
        files.add(
            new SourceFile(
                sha256(relativePath.getBytes(StandardCharsets.UTF_8)),
                relativePath,
                fileName(path).endsWith(".java") ? "JAVA" : "CONFIG",
                sha256(bytes),
                bytes.length));
      } catch (IOException exception) {
        warnings.add("READ_FAILED: " + relativePath);
      }
    }

    private byte[] readBounded(Path path, long expectedSize) throws IOException {
      try (var channel =
          Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        long size = channel.size();
        if (size != expectedSize || size > maxFileBytes || size > maxTotalBytes - totalBytes) {
          throw new IOException("FILE_CHANGED");
        }
        // One extra byte detects growth without allocating an unbounded buffer.
        ByteBuffer buffer = ByteBuffer.allocate((int) size + 1);
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
          if (Thread.currentThread().isInterrupted()) {
            throw new IOException("SCAN_CANCELLED");
          }
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
      }
    }

    @Override
    public FileVisitResult visitFileFailed(Path path, IOException exception) {
      warnings.add("READ_FAILED");
      return shouldStop() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    private Snapshot snapshot() {
      files.sort(Comparator.comparing(SourceFile::relativePath));
      return new Snapshot(UUID.randomUUID().toString(), List.copyOf(files), List.copyOf(warnings));
    }

    private String fileName(Path path) {
      return path.getFileName() == null
          ? ""
          : path.getFileName().toString().toLowerCase(Locale.ROOT);
    }
  }
}
