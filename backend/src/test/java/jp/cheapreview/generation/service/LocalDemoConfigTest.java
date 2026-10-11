package jp.cheapreview.generation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import jp.cheapreview.generation.dto.SettingsDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class LocalDemoConfigTest {
  @TempDir Path root;
  private static final String KEY = "test-only-key-000";

  private LocalDemoConfig config(String content) throws Exception {
    Path path = root.resolve("demo.env");
    Files.writeString(path, content);
    return new LocalDemoConfig(path.toString());
  }

  @Test
  void explicitActivationLoadsOnlyConfiguredFileAndDoesNotReturnSecrets() throws Exception {
    var config =
        config(
            "CHEAPREVIEW_DEMO_MODEL=fixture-model\nCHEAPREVIEW_DEMO_GEMINI_KEY="
                + KEY
                + "\nGOOGLE_APPLICATION_CREDENTIALS=missing-credentials.json\n");
    var settings = new GenerationSettings(config, "real");
    assertTrue(settings.status().demoAvailable());
    assertFalse(settings.status().configured());
    var status = settings.useDemo();
    assertEquals(SettingsDtos.Source.DEMO, status.source());
    assertEquals(KEY, settings.capture(status.version(), status.modelId()).key());
    assertFalse(new ObjectMapper().writeValueAsString(status).contains(KEY));
    assertFalse(config.load().toString().contains(KEY));
    settings.clear();
    assertFalse(settings.status().configured());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not dotenv",
        "CHEAPREVIEW_DEMO_MODEL=fixture-model",
        "CHEAPREVIEW_DEMO_MODEL=fixture-model\nCHEAPREVIEW_DEMO_MODEL=other",
        "CHEAPREVIEW_DEMO_MODEL=bad/model\nCHEAPREVIEW_DEMO_GEMINI_KEY=test-only-key-000"
      })
  void invalidConfigHasFixedErrorAndDoesNotReplaceExistingSettings(String content)
      throws Exception {
    var settings = new GenerationSettings(config(content), "real");
    var before = settings.update(new SettingsDtos.Update("fixture-model", KEY));
    assertTrue(
        java.util.Set.of("INVALID_DEMO_CONFIG", "INVALID_DEMO_MODEL", "INVALID_DEMO_KEY")
            .contains(assertThrows(ResponseStatusException.class, settings::useDemo).getReason()));
    assertEquals(before, settings.status());
  }

  @Test
  void missingAndOversizeConfigAreRejected() throws Exception {
    assertEquals(
        "DEMO_NOT_CONFIGURED",
        assertThrows(ResponseStatusException.class, () -> new LocalDemoConfig("").load())
            .getReason());
    var oversized = config("#" + "x".repeat(8192));
    assertEquals(
        "INVALID_DEMO_CONFIG",
        assertThrows(ResponseStatusException.class, oversized::load).getReason());
  }

  @Test
  void bomAndQuotedValuesAreReadWithoutExpansion() throws Exception {
    var quoted =
        config(
            "\uFEFF# local configuration\nCHEAPREVIEW_DEMO_MODEL=\"fixture-model\"\n"
                + "CHEAPREVIEW_DEMO_GEMINI_KEY='"
                + KEY
                + "'\n");
    assertEquals("fixture-model", quoted.load().modelId());
    assertEquals(KEY, quoted.load().key());
    assertEquals(
        "INVALID_DEMO_MODEL",
        assertThrows(
                ResponseStatusException.class,
                config("CHEAPREVIEW_DEMO_MODEL=${MODEL}\nCHEAPREVIEW_DEMO_GEMINI_KEY=" + KEY)::load)
            .getReason());
  }

  @Test
  void stubNeverLoadsLocalConfigAndRejectsRealCredentials() throws Exception {
    var settings = new GenerationSettings(config("invalid secret config"), "stub");
    assertFalse(settings.status().demoAvailable());
    assertEquals(SettingsDtos.Source.STUB, settings.useStub().source());
    assertEquals(
        "STUB_ONLY_MODE",
        assertThrows(ResponseStatusException.class, settings::useDemo).getReason());
    assertThrows(
        ResponseStatusException.class,
        () -> settings.update(new SettingsDtos.Update("fixture-model", KEY)));
    assertThrows(
        ResponseStatusException.class,
        () -> new GenerationSettings(new LocalDemoConfig(""), "real").useStub());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "x",
        "fixture.with+slash/equals=",
        "\"fixture.with+slash/equals=\"",
        "'fixture.with+slash/equals='"
      })
  void opaqueKeysAndOuterQuotesAreAcceptedWithoutChangingTheValue(String configuredKey)
      throws Exception {
    var config =
        config(
            "CHEAPREVIEW_DEMO_MODEL=fixture-model\nCHEAPREVIEW_DEMO_GEMINI_KEY=  "
                + configuredKey
                + "  \n");
    String expected =
        configuredKey.startsWith("\"") || configuredKey.startsWith("'")
            ? configuredKey.substring(1, configuredKey.length() - 1)
            : configuredKey;
    assertEquals(expected, config.load().key());
    var settings = new GenerationSettings(config, "real");
    var status = settings.update(new SettingsDtos.Update("fixture-model", expected));
    assertEquals(expected, settings.capture(status.version(), status.modelId()).key());
  }

  @Test
  void keyLengthDoesNotAssumeProviderFormatButStillHasAResourceLimit() throws Exception {
    var settings = new GenerationSettings(new LocalDemoConfig(""), "real");
    assertTrue(
        settings.update(new SettingsDtos.Update("fixture-model", "x".repeat(4096))).configured());
    var tooLong =
        config(
            "CHEAPREVIEW_DEMO_MODEL=fixture-model\nCHEAPREVIEW_DEMO_GEMINI_KEY="
                + "x".repeat(4097));
    assertEquals(
        "INVALID_DEMO_KEY", assertThrows(ResponseStatusException.class, tooLong::load).getReason());
    assertEquals(
        "INVALID_API_KEY",
        assertThrows(
                ResponseStatusException.class,
                () -> settings.update(new SettingsDtos.Update("fixture-model", "x".repeat(4097))))
            .getReason());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "bad key", "bad\tkey", "bad\rkey", "bad\nkey", "bad\u007fkey", "非ASCII"})
  void unsafeHeaderValuesAreRejectedAndExistingSettingsAreKept(String value) {
    var settings = new GenerationSettings(new LocalDemoConfig(""), "real");
    var before = settings.update(new SettingsDtos.Update("fixture-model", KEY));
    assertEquals(
        "INVALID_API_KEY",
        assertThrows(
                ResponseStatusException.class,
                () -> settings.update(new SettingsDtos.Update("fixture-model", value)))
            .getReason());
    assertEquals(before, settings.status());
    assertFalse(ApiKeyValidation.isSafeHeaderValue(value));
  }
}
