package jp.cheapreview.budget.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import jp.cheapreview.search.dto.CodeSearch.Mode;

public final class BudgetPreview {
  private BudgetPreview() {}

  public record Request(
      String workspaceId,
      String snapshotId,
      String question,
      Mode mode,
      List<String> selectedFileIds,
      @JsonDeserialize(using = WholeTokens.class) long inputBudgetTokens,
      String modelId,
      String targetFileId,
      String targetMethodId,
      long revision) {}

  public record Range(int beginLine, int endLine) {}

  public record Chunk(
      String fileId,
      String relativePath,
      boolean mandatory,
      List<String> methodIds,
      List<Range> ranges,
      List<Range> omittedRanges,
      String code,
      List<String> reasons) {}

  public record Excluded(String fileId, String relativePath, String reason) {}

  public record Counts(long ascii, long japanese, long other, long tokens) {}

  public record Cost(
      String currency,
      BigDecimal inputPerMillion,
      BigDecimal outputPerMillion,
      String checkedOn,
      BigDecimal inputCost,
      BigDecimal maximumOutputCost,
      BigDecimal total) {}

  public record Formula(
      BigDecimal asciiWeight,
      BigDecimal japaneseWeight,
      BigDecimal otherWeight,
      BigDecimal marginRate,
      long minimumMargin,
      String version) {}

  public record Result(
      String previewId,
      String snapshotId,
      long revision,
      String requestDigest,
      String configVersion,
      Instant expiresAt,
      Counts counts,
      long mandatoryPromptTokens,
      long relatedAdditionalTokens,
      long historyTokens,
      int historyTurnsIncluded,
      long marginTokens,
      long minimumBudgetTokens,
      long mandatoryMinimumBudgetTokens,
      long maximumOutputTokens,
      boolean canExecute,
      List<Chunk> selectedChunks,
      List<Excluded> excludedCandidates,
      List<String> warnings,
      Cost estimatedCost,
      String promptMaterial,
      Formula formula) {}
}
