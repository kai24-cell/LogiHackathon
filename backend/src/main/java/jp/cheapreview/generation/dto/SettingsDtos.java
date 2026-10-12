package jp.cheapreview.generation.dto;

public final class SettingsDtos {
  private SettingsDtos() {}

  public record Update(String modelId, String apiKey) {
    @Override
    public String toString() {
      return "SettingsUpdate[REDACTED]";
    }
  }

  public enum Source {
    NONE,
    USER,
    DEMO,
    STUB
  }

  public record Status(
      String modelId,
      boolean configured,
      String version,
      Source source,
      boolean demoAvailable,
      boolean stubAvailable) {}
}
