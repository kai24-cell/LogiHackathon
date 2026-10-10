package jp.cheapreview.search.dto;

import java.util.List;

public final class CodeSearch {
  private CodeSearch() {}

  public enum Mode {
    SERVICE_REVIEW,
    CLASS_EXPLAIN,
    PROJECT_STRUCTURE,
    AUTH_ANALYSIS
  }

  public record Request(String question, List<String> selectedFileIds, Mode mode) {}

  public record Weights(double cosine, double dependency, double roleFit) {}

  public record Score(
      double cosine,
      double dependency,
      double roleFit,
      double cosineContribution,
      double dependencyContribution,
      double roleContribution,
      double total) {}

  public record Candidate(
      int rank,
      String fileId,
      String relativePath,
      boolean primarySelected,
      Integer dependencyDistance,
      String bestMethodId,
      int beginLine,
      Score score,
      List<String> matchingTerms,
      List<String> roles,
      List<String> reasons) {}

  public record Result(
      String snapshotId,
      Mode mode,
      String formulaVersion,
      Weights weights,
      int documentCount,
      List<Candidate> candidates,
      List<String> warnings) {}
}
