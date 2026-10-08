package jp.cheapreview.analysis.dto;

import java.util.List;

public final class JavaAnalysis {
  private JavaAnalysis() {}

  public enum ParseStatus {
    PARSED,
    FAILED
  }

  public enum EdgeKind {
    TYPE_REFERENCE,
    FIELD_DI,
    CONSTRUCTOR_DI,
    METHOD_CALL
  }

  public enum Resolution {
    RESOLVED,
    HEURISTIC
  }

  public record Range(int beginLine, int endLine) {}

  public record Role(String name, String evidence, double confidence) {}

  public record Field(String name, String type, Range range, List<String> annotations) {}

  public record Method(
      String methodId,
      String declaringType,
      String name,
      String signature,
      boolean constructor,
      Range range,
      String body,
      List<String> annotations,
      List<String> referencedTypes) {}

  public record Type(
      String typeId,
      String qualifiedName,
      String kind,
      Range range,
      List<String> annotations,
      List<Role> roles,
      List<Field> fields,
      List<Method> methods) {}

  public record File(
      String fileId,
      String relativePath,
      ParseStatus parseStatus,
      String packageName,
      List<String> imports,
      List<Type> types,
      List<String> warnings) {}

  public record Edge(
      String fromId,
      String toId,
      EdgeKind kind,
      double confidence,
      Resolution resolution,
      String relativePath,
      int line) {}

  public record Unresolved(
      String fromId,
      EdgeKind kind,
      String reference,
      String reason,
      String relativePath,
      int line) {}

  public record Result(
      String snapshotId,
      List<File> files,
      List<Edge> edges,
      List<Unresolved> unresolved,
      List<String> warnings) {}
}
