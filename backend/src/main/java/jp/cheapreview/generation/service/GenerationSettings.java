package jp.cheapreview.generation.service;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import jp.cheapreview.generation.dto.SettingsDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** キーはこのメモリ状態だけに保持し、応答・toString・ファイルへ含めない。 */
@Service
public class GenerationSettings {
  private String modelId = "";
  private String key;
  private String version = UUID.randomUUID().toString();
  private SettingsDtos.Source source = SettingsDtos.Source.NONE;
  private final LocalDemoConfig demo;
  private final boolean stub;

  public GenerationSettings(
      LocalDemoConfig demo, @Value("${cheapreview.generation.provider:real}") String provider) {
    if (!provider.equals("real") && !provider.equals("stub"))
      throw new IllegalArgumentException("Invalid provider mode");
    this.demo = demo;
    stub = provider.equals("stub");
  }

  public record Credentials(
      String modelId, String key, String version, SettingsDtos.Source source) {
    public Credentials(String modelId, String key, String version) {
      this(modelId, key, version, SettingsDtos.Source.USER);
    }

    @Override
    public String toString() {
      return "Credentials[REDACTED]";
    }
  }

  public synchronized SettingsDtos.Status status() {
    return new SettingsDtos.Status(
        modelId, key != null, version, source, !stub && demo.available(), stub);
  }

  /** 新しいキーをJSON本文で受け取り、旧設定参照を置換する。空キーの保存は認めない。 */
  public synchronized SettingsDtos.Status update(SettingsDtos.Update update) {
    if (stub) throw new ResponseStatusException(HttpStatus.CONFLICT, "STUB_ONLY_MODE");
    if (update == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SETTINGS");
    if (update.modelId() == null || !update.modelId().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MODEL_ID");
    if (update.apiKey() == null || !update.apiKey().matches("[a-zA-Z0-9_-]{8,256}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_API_KEY");

    // 検証完了前には既存設定を変更せず、例外にも入力値を含めない。
    modelId = update.modelId();
    key = update.apiKey();
    source = SettingsDtos.Source.USER;
    version = UUID.randomUUID().toString();
    return status();
  }

  /** キーをWebへ返さず、デモ専用ローカル設定から通常のメモリ設定へ切り替える。 */
  public synchronized SettingsDtos.Status useDemo() {
    if (stub) throw new ResponseStatusException(HttpStatus.CONFLICT, "STUB_ONLY_MODE");
    var credentials = demo.load();
    modelId = credentials.modelId();
    key = credentials.key();
    source = SettingsDtos.Source.DEMO;
    version = UUID.randomUUID().toString();
    return status();
  }

  /** 固定応答では認証情報を読まず、実通信しないproviderにだけ仮設定を許可する。 */
  public synchronized SettingsDtos.Status useStub() {
    if (!stub) throw new ResponseStatusException(HttpStatus.CONFLICT, "STUB_NOT_ENABLED");
    modelId = "stub-fixture";
    key = "stub-only-placeholder";
    source = SettingsDtos.Source.STUB;
    version = UUID.randomUUID().toString();
    return status();
  }

  public synchronized Credentials capture(String expectedVersion, String expectedModel) {
    if (key == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GEMINI_NOT_CONFIGURED");
    if (!version.equals(expectedVersion) || !modelId.equals(expectedModel))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_SETTINGS");
    return new Credentials(modelId, key, version, source);
  }

  public synchronized boolean current(String expectedVersion) {
    return version.equals(expectedVersion);
  }

  @PreDestroy
  public synchronized void clear() {
    key = null;
    source = SettingsDtos.Source.NONE;
    modelId = "";
    version = UUID.randomUUID().toString();
  }
}
