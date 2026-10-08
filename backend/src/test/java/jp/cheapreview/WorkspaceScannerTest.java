package jp.cheapreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspacePaths;
import jp.cheapreview.workspace.service.WorkspaceScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceScannerTest {
  @TempDir Path root;

  private Path write(String name, String text) throws Exception {
    Path path = root.resolve(name);
    Files.createDirectories(path.getParent());
    Files.writeString(path, text);
    return path;
  }

  @Test
  void defaultsExcludeTestsGeneratedSecretsAndBuildOutput() throws Exception {
    write("src/main/java/Order.java", "class Order {} // 注文");
    write("src/test/java/OrderTest.java", "class OrderTest {}");
    write("generated/Foo.java", "class Foo {}");
    write("target/Hidden.java", "class Hidden {}");
    write("credentials.java", "secret");
    write("application.yml", "spring: {}");
    var scanner = new WorkspaceScanner(1048576, 10000, 104857600, 60);
    var snapshot = scanner.scan(root.toRealPath(), new Options(false, false, false));
    assertEquals(1, snapshot.files().size());
    assertEquals("src/main/java/Order.java", snapshot.files().getFirst().relativePath());
    var expanded = scanner.scan(root.toRealPath(), new Options(true, true, true));
    assertEquals(4, expanded.files().size());
  }

  @Test
  void snapshotsHaveStableFileIdsAndChangedHashes() throws Exception {
    Path file = write("Order.java", "class Order {}");
    var scanner = new WorkspaceScanner(100, 100, 1000, 60);
    var first = scanner.scan(root, new Options(false, false, false));
    Files.writeString(file, "class Order { int value; }");
    var second = scanner.scan(root, new Options(false, false, false));
    assertEquals(first.files().getFirst().fileId(), second.files().getFirst().fileId());
    assertNotEquals(first.files().getFirst().sha256(), second.files().getFirst().sha256());
    assertNotEquals(first.snapshotId(), second.snapshotId());
  }

  @Test
  void limitsAndMalformedUtf8ProduceWarnings() throws Exception {
    write("Large.java", "x".repeat(101));
    Files.write(root.resolve("Bad.java"), new byte[] {(byte) 0xff});
    var result =
        new WorkspaceScanner(100, 100, 1000, 60).scan(root, new Options(false, false, false));
    assertTrue(result.files().isEmpty());
    assertTrue(result.warnings().stream().anyMatch(s -> s.startsWith("FILE_SIZE_LIMIT")));
    assertTrue(result.warnings().stream().anyMatch(s -> s.startsWith("READ_FAILED")));
  }

  @Test
  void rejectsNonLocalUris() {
    assertThrows(Exception.class, () -> WorkspacePaths.validateRoot("https://example.com/source"));
    assertThrows(Exception.class, () -> WorkspacePaths.validateRoot("file://remote/share/project"));
  }

  @Test
  void totalSizeBoundaryAndTraversalLimitAreReported() throws Exception {
    write("First.java", "12345");
    write("Second.java", "12345");
    Options options = new Options(false, false, false);
    var exact = new WorkspaceScanner(5, 100, 10, 60).scan(root, options);
    assertEquals(2, exact.files().size());
    var tooSmall = new WorkspaceScanner(5, 100, 9, 60).scan(root, options);
    assertEquals(1, tooSmall.files().size());
    assertTrue(tooSmall.warnings().contains("TOTAL_SIZE_LIMIT"));
    var limited = new WorkspaceScanner(5, 1, 10, 60).scan(root, options);
    assertTrue(limited.warnings().contains("FILE_COUNT_LIMIT"));
  }

  @Test
  void refusesPathsOutsideRegisteredRoot() throws Exception {
    Path allowed = Files.createDirectory(root.resolve("allowed"));
    Path outside = write("outside.java", "class Outside {}");
    assertThrows(
        java.io.IOException.class, () -> WorkspacePaths.requireWithinRoot(outside, allowed));
  }

  @Test
  void acceptsEquivalentRootSpellingsAndRejectsTraversalOutsideRoot() throws Exception {
    Path allowed = Files.createDirectory(root.resolve("allowed"));
    Path file = write("allowed/Allowed.java", "class Allowed {}");
    Path outside = write("Outside.java", "class Outside {}");
    Path equivalentRoot = allowed.resolve(".");

    WorkspacePaths.requireWithinRoot(file, equivalentRoot);
    var snapshot =
        new WorkspaceScanner(100, 100, 1000, 60)
            .scan(equivalentRoot, new Options(false, false, false));
    assertEquals(1, snapshot.files().size());
    assertEquals("Allowed.java", snapshot.files().getFirst().relativePath());
    assertThrows(
        java.io.IOException.class,
        () ->
            WorkspacePaths.requireWithinRoot(
                allowed.resolve("..").resolve(outside.getFileName()), equivalentRoot));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void acceptsWindowsShortRootSpelling() throws Exception {
    Path allowed = Files.createDirectory(root.resolve("long-workspace-directory"));
    Files.writeString(allowed.resolve("Allowed.java"), "class Allowed {}");
    Process shortPath =
        new ProcessBuilder("cmd.exe", "/c", "for %I in (\"" + allowed + "\") do @echo %~sI")
            .redirectErrorStream(true)
            .start();
    assertTrue(shortPath.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, shortPath.exitValue());
    Path shortRoot =
        Path.of(
            new String(
                    shortPath.getInputStream().readAllBytes(),
                    java.nio.charset.Charset.defaultCharset())
                .strip());
    assertEquals(allowed.toRealPath(), shortRoot.toRealPath());
    WorkspacePaths.requireWithinRoot(shortRoot, shortRoot);
    var snapshot =
        new WorkspaceScanner(100, 100, 1000, 60).scan(shortRoot, new Options(false, false, false));
    assertEquals(1, snapshot.files().size());
    assertEquals("Allowed.java", snapshot.files().getFirst().relativePath());
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void rejectsJunctionRootsAndDoesNotFollowJunctionChildren() throws Exception {
    Path allowed = Files.createDirectory(root.resolve("allowed"));
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.writeString(allowed.resolve("Allowed.java"), "class Allowed {}");
    Files.writeString(outside.resolve("Outside.java"), "class Outside {}");
    Path junction = allowed.resolve("linked");
    Process create =
        new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertTrue(create.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, create.exitValue());
    try {
      assertThrows(
          java.io.IOException.class,
          () -> WorkspacePaths.validateRoot(junction.toUri().toString()));
      var snapshot =
          new WorkspaceScanner(100, 100, 1000, 60).scan(allowed, new Options(false, false, false));
      assertEquals(1, snapshot.files().size());
      assertEquals("Allowed.java", snapshot.files().getFirst().relativePath());
    } finally {
      // Delete only the junction entry, never its target or a recursive tree.
      Files.deleteIfExists(junction);
    }
  }
}
