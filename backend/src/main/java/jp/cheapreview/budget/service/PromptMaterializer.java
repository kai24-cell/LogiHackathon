package jp.cheapreview.budget.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jp.cheapreview.budget.dto.BudgetPreview.Chunk;
import jp.cheapreview.budget.dto.BudgetPreview.Request;

/** 推定と組み立てで共通の送信材料。APIキーやHTTPの輸送エスケープを含めない。 */
public final class PromptMaterializer {
  public record Bundle(String system, String schema, String contents) {
    @Override
    public String toString() {
      return "PromptBundle[REDACTED]";
    }
  }

  private static final String SYSTEM = resource("system.txt");
  private static final String SCHEMA = resource("answer-schema.json");
  private static final String END = resource("english-output.txt").stripTrailing();

  /** 指示・schema・参照一覧・コード・質問を一度だけ連結し、最終判定に使う。 */
  static String materialize(Request request, List<Chunk> chunks) {
    var bundle = bundle(request, chunks);
    return bundle.system() + "\nANSWER_SCHEMA\n" + bundle.schema() + bundle.contents();
  }

  /** system/schemaを重ねずAPIへ分離して渡し、概算と同じ材料を使う。 */
  public static Bundle bundle(Request request, List<Chunk> chunks) {
    var text =
        new StringBuilder()
            .append("\nMODE: ")
            .append(request.mode())
            .append("\n")
            .append(instruction(request))
            .append("\nHISTORY: none (initial question)\nREFERENCES\n");
    for (int i = 0; i < chunks.size(); i++) {
      var chunk = chunks.get(i);
      text.append("ref-")
          .append(i + 1)
          .append(" ")
          .append(chunk.relativePath())
          .append(" fileId=")
          .append(chunk.fileId())
          .append(" ranges=")
          .append(chunk.ranges())
          .append("\n");
    }
    text.append("\nSELECTED_CODE\n");
    for (int i = 0; i < chunks.size(); i++)
      text.append("ref-").append(i + 1).append("\n").append(chunks.get(i).code()).append("\n");
    String contents =
        text.append("\nQUESTION\n").append(request.question()).append("\n").append(END).toString();
    return new Bundle(SYSTEM, SCHEMA, contents);
  }

  private static String resource(String name) {
    try (var stream = PromptMaterializer.class.getResourceAsStream("/prompts/" + name)) {
      if (stream == null) throw new IllegalStateException("Missing prompt resource");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot load prompt resource");
    }
  }

  private static String instruction(Request request) {
    return switch (request.mode()) {
      case SERVICE_REVIEW -> resource("service-review.txt").stripTrailing();
      case CLASS_EXPLAIN ->
          resource("class-explain.txt").stripTrailing() + " " + request.targetFileId();
      case PROJECT_STRUCTURE -> resource("project-structure.txt").stripTrailing();
      case AUTH_ANALYSIS -> resource("auth-analysis.txt").stripTrailing();
    };
  }
}
