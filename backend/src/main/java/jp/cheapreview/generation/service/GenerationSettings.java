package jp.cheapreview.generation.service;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import jp.cheapreview.generation.dto.SettingsDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** キーはこのメモリ状態だけに保持し、応答・toString・ファイルへ含めない。 */
@Service
public class GenerationSettings {
  private String modelId = "";
  private String key;
  private String version = UUID.randomUUID().toString();

  public record Credentials(String modelId, String key, String version) {
    @Override
    public String toString() {
      return "Credentials[REDACTED]";
    }
  }

  public synchronized SettingsDtos.Status status() {
    return new SettingsDtos.Status(modelId, key != null, version);
  }

  /** 新しいキーをJSON本文で受け取り、旧設定参照を置換する。空キーの保存は認めない。 */
  public synchronized SettingsDtos.Status update(SettingsDtos.Update update) {
    if (update == null
        || update.modelId() == null
        || !update.modelId().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}")
        || update.apiKey() == null
        || !update.apiKey().matches("[a-zA-Z0-9_-]{8,256}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SETTINGS");
    modelId = update.modelId();
    key = update.apiKey();
    version = UUID.randomUUID().toString();
    return status();
  }

  public synchronized Credentials capture(String expectedVersion, String expectedModel) {
    if (key == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GEMINI_NOT_CONFIGURED");
    if (!version.equals(expectedVersion) || !modelId.equals(expectedModel))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_SETTINGS");
    return new Credentials(modelId, key, version);
  }

  public synchronized boolean current(String expectedVersion) {
    return version.equals(expectedVersion);
  }

  @PreDestroy
  public synchronized void clear() {
    key = null;
    modelId = "";
    version = UUID.randomUUID().toString();
  }
}
