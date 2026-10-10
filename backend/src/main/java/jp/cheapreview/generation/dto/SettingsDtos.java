package jp.cheapreview.generation.dto;

public final class SettingsDtos {
  private SettingsDtos() {}

  public record Update(String modelId, String apiKey) {
    @Override
    public String toString() {
      return "SettingsUpdate[REDACTED]";
    }
  }

  public record Status(String modelId, boolean configured, String version) {}
}
