package jp.cheapreview.workspace.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public final class WorkspacePaths {
  private WorkspacePaths() {}

  public static Path validateRoot(String uri) throws IOException {
    URI parsed = URI.create(uri);
    if (!"file".equals(parsed.getScheme()) || parsed.getAuthority() != null) {
      throw new IOException("INVALID_SCOPE");
    }
    Path path = Path.of(parsed).toAbsolutePath().normalize();
    rejectLinks(path);
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("INVALID_SCOPE");
    }
    return path.toRealPath();
  }

  public static void requireWithinRoot(Path path, Path root) throws IOException {
    if (!path.toAbsolutePath().normalize().startsWith(root)) {
      throw new IOException("INVALID_SCOPE");
    }
    rejectLinks(path);
    if (!path.toRealPath().startsWith(root)) {
      throw new IOException("INVALID_SCOPE");
    }
  }

  private static void rejectLinks(Path path) throws IOException {
    for (Path current = path; current != null; current = current.getParent()) {
      BasicFileAttributes attributes =
          Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || attributes.isOther()) {
        throw new IOException("INVALID_SCOPE");
      }
    }
  }
}
