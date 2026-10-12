package jp.cheapreview.generation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jp.cheapreview.budget.dto.BudgetPreview;
import jp.cheapreview.budget.service.BudgetPreviewService;
import jp.cheapreview.budget.service.PromptMaterializer;
import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.generation.dto.SettingsDtos;
import jp.cheapreview.search.dto.CodeSearch.Mode;
import jp.cheapreview.workspace.dto.WorkspaceDtos.Options;
import jp.cheapreview.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties =
        "cheapreview.connection-token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
// ケース間のworkspace・job・キー設定を持ち越さず、製品の保持上限も変更しない。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GenerationIntegrationTest {
  @Autowired MockMvc mvc;
  private static final String KEY = "test-only-key-000";
  private static final String ANSWER =
      "{\"summary\":\"Explanation\",\"sections\":[{\"title\":\"Flow\",\"body\":\"Evidence\",\"code\":\"\",\"referenceIds\":[\"ref-1\",\"unknown\"]}],\"findings\":[],\"limitations\":[]}";
  @Autowired WorkspaceService workspaces;
  @Autowired BudgetPreviewService budgets;
  @Autowired GenerationService generation;
  @Autowired jp.cheapreview.conversation.service.ConversationService conversations;
  @Autowired GenerationSettings settings;
  @Autowired AnswerValidator validator;
  @Autowired ObjectMapper mapper;
  @MockitoBean GeminiClient provider;
  @MockitoBean jp.cheapreview.translation.service.TranslationClient translator;
  @TempDir Path root;
  String workspace;
  String snapshot;
  String file;
  SettingsDtos.Status configured;

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(root.resolve("Service.java"), "class Service { public void run() {} }");
    workspace = workspaces.register(root.toUri().toString()).workspaceId();
    String scan = workspaces.startScan(workspace, new Options(false, false, false));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (workspaces.getJob(scan).state()
            == jp.cheapreview.workspace.dto.WorkspaceDtos.JobState.QUEUED
        || workspaces.getJob(scan).state()
            == jp.cheapreview.workspace.dto.WorkspaceDtos.JobState.SCANNING) {
      if (System.nanoTime() > deadline) fail("Scan timeout");
      Thread.sleep(10);
    }
    var capture = workspaces.getSnapshot(workspace);
    snapshot = capture.snapshotId();
    file = capture.files().getFirst().fileId();
    configured = settings.update(new SettingsDtos.Update("fixture-model", KEY));
    when(provider.generate(any(), any(), anyLong(), anyLong())).thenReturn(reply(ANSWER));
    when(translator.configured()).thenReturn(true);
    when(translator.translate(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(translator.translate(any(), any()))
        .thenAnswer(invocation -> translator.translate(invocation.getArgument(0)));
  }

  private GeminiClient.Reply reply(String raw) {
    return new GeminiClient.Reply(
        raw, new GenerationDtos.Usage(23L, 12L, null, null, 35L), "STOP", null);
  }

  private BudgetPreview.Request input(Mode mode, long budget) {
    return new BudgetPreview.Request(
        workspace,
        snapshot,
        "処理を説明して",
        mode,
        List.of(file),
        budget,
        "fixture-model",
        mode == Mode.CLASS_EXPLAIN ? file : null,
        null,
        1);
  }

  private GenerationDtos.Start request(BudgetPreview.Request input) {
    var preview = budgets.preview(workspaces.getCapture(workspace, snapshot), input);
    return new GenerationDtos.Start(
        preview.previewId(), UUID.randomUUID().toString(), configured.version(), input);
  }

  private GenerationDtos.Job completed(String id) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    GenerationDtos.Job job;
    do {
      job = generation.get(id);
      if (job.state() != GenerationDtos.State.QUEUED
          && job.state() != GenerationDtos.State.GENERATING
          && job.state() != GenerationDtos.State.TRANSLATING
          && (job.translation() == null
              || job.translation().status()
                  != jp.cheapreview.translation.dto.TranslationDtos.Status.RUNNING)) {
        Thread.sleep(20);
        return job;
      }
      if (System.nanoTime() > deadline) fail("Generation timeout");
      Thread.sleep(10);
    } while (true);
  }

  @Test
  void translationFailureRetainsEnglishAndRetryDoesNotCallGeminiAgain() throws Exception {
    when(translator.translate(any()))
        .thenThrow(
            new jp.cheapreview.translation.service.TranslationFailure("TRANSLATION_TIMEOUT"));
    var started = generation.start(request(input(Mode.SERVICE_REVIEW, 8192)));
    var failed = completed(started.jobId());
    assertEquals(GenerationDtos.State.TRANSLATION_FAILED, failed.state());
    assertEquals("Explanation", failed.result().answerEnglish().summary());
    assertEquals(35L, failed.result().usage().totalTokens());
    assertEquals("TRANSLATION_TIMEOUT", failed.translation().errorCode());
    org.mockito.Mockito.doAnswer(invocation -> invocation.getArgument(0))
        .when(translator)
        .translate(any());
    generation.translate(started.jobId());
    var retried = completed(started.jobId());
    assertEquals(GenerationDtos.State.SUCCEEDED, retried.state());
    assertEquals(2, retried.translation().attempt());
    assertEquals(failed.result(), retried.result());
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancellingFirstOrRetriedTranslationKeepsEnglishAndRejectsLateUpdates(boolean retry)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    GenerationDtos.Started started = null;
    if (retry) {
      org.mockito.Mockito.doAnswer(
              invocation -> {
                List<String> contents = invocation.getArgument(0);
                var translated =
                    new java.util.ArrayList<>(
                        contents.stream().map(value -> "日本語 " + value).toList());
                translated.set(0, "");
                return translated;
              })
          .when(translator)
          .translate(any());
      started = generation.start(request(input(Mode.SERVICE_REVIEW, 8192)));
      assertTrue(completed(started.jobId()).translation().partialTranslation());
    }
    org.mockito.Mockito.doAnswer(
            invocation -> {
              entered.countDown();
              while (release.getCount() > 0) {
                try {
                  release.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                  /* 遅延結果が到着するproviderを再現。 */
                }
              }
              return invocation.getArgument(0);
            })
        .when(translator)
        .translate(any());
    if (retry) generation.translate(started.jobId());
    else started = generation.start(request(input(Mode.SERVICE_REVIEW, 8192)));
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    var before = generation.get(started.jobId()).result();
    var cancelled = generation.cancel(started.jobId());
    assertEquals(GenerationDtos.State.TRANSLATION_FAILED, cancelled.state());
    assertEquals("TRANSLATION_CANCELLED", cancelled.translation().errorCode());
    assertEquals(before, cancelled.result());
    if (retry)
      assertTrue(
          cancelled.translation().warnings().stream()
              .anyMatch(warning -> warning.field().equals("summary")));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (generation.activeJob().jobId() != null) {
      if (System.nanoTime() > deadline) fail("Translation active not released");
      Thread.sleep(10);
    }
    release.countDown();
    Thread.sleep(100);
    assertEquals(cancelled, generation.get(started.jobId()));
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void allModesAndDuplicateRequestUseOneProviderCallEach() throws Exception {
    for (var mode : Mode.values()) {
      var start = request(input(mode, 8192));
      var job = generation.start(start);
      assertEquals(job, generation.start(start));
      var done = completed(job.jobId());
      assertEquals(GenerationDtos.State.SUCCEEDED, done.state());
      assertEquals(
          List.of("ref-1"), done.result().answerEnglish().sections().getFirst().referenceIds());
      assertEquals(file, done.result().references().getFirst().fileId());
      assertTrue(done.result().warnings().contains("不正な回答参照を非表示にしました。"));
      assertNull(done.result().usage().thoughtTokens());
      assertFalse(mapper.writeValueAsString(done).contains(KEY));
    }
    verify(provider, times(4)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void safeProviderDiagnosticsReachFailedJobWithoutRawErrorsOrSecrets() throws Exception {
    var details =
        new GenerationDtos.ErrorDetails(
            404,
            GenerationDtos.ApiStatus.NOT_FOUND,
            GenerationDtos.ApiStage.MODEL_INFO,
            GenerationDtos.LimitType.UNKNOWN);
    when(provider.generate(any(), any(), anyLong(), anyLong()))
        .thenThrow(new GenerationFailure("MODEL_NOT_FOUND", details));
    var done = completed(generation.start(request(input(Mode.SERVICE_REVIEW, 8192))).jobId());
    assertEquals(GenerationDtos.State.FAILED, done.state());
    assertEquals("MODEL_NOT_FOUND", done.errorCode());
    assertEquals(details, done.errorDetails());
    assertNull(done.result());
    assertFalse(mapper.writeValueAsString(done).contains(KEY));
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void runningInputIsFrozenAndConcurrentOrChangedIdReuseIsRejected() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var seen = new AtomicReference<PromptMaterializer.Bundle>();
    when(provider.generate(any(), any(), anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              seen.set(invocation.getArgument(0));
              entered.countDown();
              release.await(3, TimeUnit.SECONDS);
              return reply(ANSWER);
            });
    var start = request(input(Mode.SERVICE_REVIEW, 8192));
    var job = generation.start(start);
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals(job.jobId(), generation.activeJob().jobId());
      assertEquals(
          429,
          assertThrows(
                  ResponseStatusException.class,
                  () -> generation.start(request(input(Mode.SERVICE_REVIEW, 8192))))
              .getStatusCode()
              .value());
      var changed =
          new GenerationDtos.Start(
              "different-preview", start.requestId(), start.settingsVersion(), start.input());
      assertEquals(
          409,
          assertThrows(ResponseStatusException.class, () -> generation.start(changed))
              .getStatusCode()
              .value());
      assertTrue(seen.get().contents().contains(start.input().question()));
      assertEquals(job, generation.start(start));
    } finally {
      release.countDown();
      completed(job.jobId());
    }
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void insufficientBudgetStaleSettingsAndChangedFileNeverCallProvider() throws Exception {
    Files.writeString(
        root.resolve("Service.java"),
        "class Service { public void run() { String x=\"" + "x".repeat(4000) + "\"; } }");
    // 改変検知は保存済みsnapshotのhashで確認し、未走査の内容を送信材料へ混ぜない。
    var start = request(input(Mode.SERVICE_REVIEW, 8192));
    assertEquals(
        409,
        assertThrows(ResponseStatusException.class, () -> generation.start(start))
            .getStatusCode()
            .value());
    Files.writeString(root.resolve("Service.java"), "class Service { public void run() {} }");
    settings.clear();
    configured = settings.update(new SettingsDtos.Update("fixture-model", "replacement-test-key"));
    assertEquals(
        409,
        assertThrows(ResponseStatusException.class, () -> generation.start(start))
            .getStatusCode()
            .value());
    var small = request(input(Mode.SERVICE_REVIEW, 1024));
    assertEquals(
        422,
        assertThrows(ResponseStatusException.class, () -> generation.start(small))
            .getStatusCode()
            .value());
    verifyNoInteractions(provider);
  }

  @Test
  void cancellationDoesNotPublishLateSuccessOrRepeatGeneration() throws Exception {
    var entered = new CountDownLatch(1);
    when(provider.generate(any(), any(), anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              Thread.sleep(3000);
              return reply(ANSWER);
            });
    var start = request(input(Mode.SERVICE_REVIEW, 8192));
    var job = generation.start(start);
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    assertEquals(GenerationDtos.State.CANCELLED, generation.cancel(job.jobId()).state());
    assertEquals(GenerationDtos.State.CANCELLED, completed(job.jobId()).state());
    assertEquals(job, generation.start(start));
    assertNull(generation.get(job.jobId()).result());
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void malformedJsonFailsWithoutRepairOrKeyExposure() throws Exception {
    when(provider.generate(any(), any(), anyLong(), anyLong())).thenReturn(reply("invalid " + KEY));
    var job = completed(generation.start(request(input(Mode.SERVICE_REVIEW, 8192))).jobId());
    assertEquals(GenerationDtos.State.FAILED, job.state());
    assertEquals("INVALID_ANSWER", job.errorCode());
    assertFalse(mapper.writeValueAsString(job).contains(KEY));
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @ParameterizedTest
  @CsvSource({"0,false", "1,false", "2,false", "0,true", "1,true", "2,true"})
  void invalidAnswerRetainsNoEncodedKeyAfterSettingsClear(int encoding, boolean truncated)
      throws Exception {
    var encoded = new StringBuilder();
    for (int index = 0; index < KEY.length(); index++) {
      char character = KEY.charAt(index);
      if (encoding == 1 || (encoding == 2 && index % 2 == 0))
        encoded.append(String.format("\\u%04X", (int) character));
      else encoded.append(character);
    }
    String suffix = truncated ? "" : "}";
    when(provider.generate(any(), any(), anyLong(), anyLong()))
        .thenReturn(reply("{\"summary\":\"" + encoded + "\"" + suffix));

    var request = request(input(Mode.SERVICE_REVIEW, 8192));
    var job = completed(generation.start(request).jobId());
    assertEquals(GenerationDtos.State.FAILED, job.state());
    assertEquals("INVALID_ANSWER", job.errorCode());
    settings.clear();

    // 公開DTOだけでなく、設定解除後も残る内部rawを確認する。テスト用キー以外は扱わない。
    var entries = (java.util.Map<?, ?>) ReflectionTestUtils.getField(generation, "jobs");
    String retained =
        (String) ReflectionTestUtils.getField(entries.get(request.requestId()), "failedRaw");
    assertEquals("{\"summary\":\"[REDACTED]\"" + suffix, retained);
    assertFalse(mapper.writeValueAsString(job).contains(KEY));
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "GEMINI_AUTH_FAILED",
        "GEMINI_RATE_LIMITED",
        "GENERATION_TIMEOUT",
        "MODEL_LIMIT_EXCEEDED",
        "GEMINI_BLOCKED"
      })
  void providerFailuresFinishOnceWithSafeCode(String code) throws Exception {
    when(provider.generate(any(), any(), anyLong(), anyLong()))
        .thenThrow(new GenerationFailure(code));
    var request = request(input(Mode.SERVICE_REVIEW, 8192));
    var started = generation.start(request);
    var job = completed(started.jobId());
    assertEquals(GenerationDtos.State.FAILED, job.state());
    assertEquals(code, job.errorCode());
    assertEquals(started, generation.start(request));
    assertFalse(mapper.writeValueAsString(job).contains(KEY));
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void strictAnswerContractRejectsUnknownMissingWrongAndDuplicateFields() {
    var input = input(Mode.SERVICE_REVIEW, 8192);
    var preview = budgets.preview(workspaces.getCapture(workspace, snapshot), input);
    for (String malformed :
        List.of(
            "{}",
            ANSWER.replace("\"Explanation\"", "null"),
            ANSWER.replace("\"summary\":", "\"extra\":0,\"summary\":"),
            ANSWER.replace("\"sections\":[", "\"sections\":[0,"),
            ANSWER + " {}",
            ANSWER.replace("\"summary\":", "\"summary\":\"dup\",\"summary\":")))
      assertThrows(GenerationFailure.class, () -> validator.validate(malformed, preview, input));
  }

  @Test
  void keyIsNeverReturnedOrPrintedAndClearInvalidatesVersion() throws Exception {
    assertFalse(settings.status().toString().contains(KEY));
    assertFalse(mapper.writeValueAsString(settings.status()).contains(KEY));
    assertFalse(new SettingsDtos.Update("fixture-model", KEY).toString().contains(KEY));
    assertFalse(settings.capture(configured.version(), "fixture-model").toString().contains(KEY));
    settings.clear();
    assertFalse(settings.status().configured());
    assertThrows(
        ResponseStatusException.class,
        () -> settings.capture(configured.version(), "fixture-model"));
  }

  @Test
  void settingsAndJobHttpContractsNeverExposeKey() throws Exception {
    String token = "a".repeat(64);
    mvc.perform(
            put("/api/v1/settings")
                .header("Host", "127.0.0.1:8765")
                .contentType("application/json")
                .content(mapper.writeValueAsString(new SettingsDtos.Update("fixture-model", KEY))))
        .andExpect(status().isUnauthorized());
    var response =
        mvc.perform(
                put("/api/v1/settings")
                    .header("Host", "127.0.0.1:8765")
                    .header("X-CheapReview-Token", token)
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(new SettingsDtos.Update("fixture-model", KEY))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains(KEY));
    configured = settings.status();
    var request = request(input(Mode.SERVICE_REVIEW, 8192));
    var start =
        mvc.perform(
                post("/api/v1/analysis/jobs")
                    .header("Host", "127.0.0.1:8765")
                    .header("X-CheapReview-Token", token)
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = mapper.readTree(start).path("jobId").asText();
    completed(id);
    var result =
        mvc.perform(
                get("/api/v1/jobs/" + id)
                    .header("Host", "127.0.0.1:8765")
                    .header("X-CheapReview-Token", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SUCCEEDED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(result.contains(KEY));
    mvc.perform(
            delete("/api/v1/settings")
                .header("Host", "127.0.0.1:8765")
                .header("X-CheapReview-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false));
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void unicodeEscapedKeyInAnswerIsRedactedAfterJsonDecoding() throws Exception {
    String encoded =
        KEY.chars()
            .mapToObj(point -> String.format("\\u%04x", point))
            .collect(java.util.stream.Collectors.joining());
    when(provider.generate(any(), any(), anyLong(), anyLong()))
        .thenReturn(reply(ANSWER.replace("Explanation", encoded)));
    var job = completed(generation.start(request(input(Mode.SERVICE_REVIEW, 8192))).jobId());
    assertEquals("[REDACTED]", job.result().answerEnglish().summary());
    assertFalse(mapper.writeValueAsString(job).contains(KEY));
  }

  @Test
  void settingsValidationIdentifiesFieldWithoutChangingPreviousConfiguration() throws Exception {
    var before = settings.status();
    String token = "a".repeat(64);
    for (var invalid :
        List.of(
            new SettingsDtos.Update("models/fixture-model", KEY),
            new SettingsDtos.Update("fixture-model", "invalid key with spaces"))) {
      String code =
          invalid.modelId().startsWith("models/") ? "INVALID_MODEL_ID" : "INVALID_API_KEY";
      var response =
          mvc.perform(
                  put("/api/v1/settings")
                      .header("Host", "127.0.0.1:8765")
                      .header("Origin", "http://127.0.0.1:8765")
                      .header("X-CheapReview-Token", token)
                      .contentType("application/json")
                      .content(mapper.writeValueAsString(invalid)))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").value(code))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertFalse(response.contains(KEY));
      assertFalse(response.contains(invalid.apiKey()));
      assertEquals(before, settings.status());
    }
    verifyNoInteractions(provider);
  }

  @Test
  void settingsAndPreviewRejectMissingConnectionAndInvalidOriginBeforeInputValidation()
      throws Exception {
    for (String path : List.of("/api/v1/settings", "/api/v1/analysis/preview")) {
      for (boolean wrongOrigin : List.of(false, true)) {
        var request = path.endsWith("settings") ? put(path) : post(path);
        request
            .header("Host", "127.0.0.1:8765")
            .header("Origin", wrongOrigin ? "http://localhost:5173" : "http://127.0.0.1:8765")
            .contentType("application/json")
            .content("{}");
        mvc.perform(request)
            .andExpect(status().is(wrongOrigin ? 403 : 401))
            .andExpect(
                jsonPath("$.code").value(wrongOrigin ? "INVALID_ORIGIN" : "INVALID_CONNECTION"));
      }
      var request = path.endsWith("settings") ? put(path) : post(path);
      mvc.perform(
              request
                  .header("Host", "127.0.0.1:8765")
                  .header("X-CheapReview-Token", "b".repeat(64))
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("INVALID_CONNECTION"));
    }
    verifyNoInteractions(provider);
  }

  @Test
  void authenticatedSettingsAndPreviewSucceedWithoutCallingGemini() throws Exception {
    String token = "a".repeat(64);
    mvc.perform(
            put("/api/v1/settings")
                .header("Host", "127.0.0.1:8765")
                .header("X-CheapReview-Token", token)
                .contentType("application/json")
                .content(mapper.writeValueAsString(new SettingsDtos.Update("fixture-model", KEY))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.modelId").value("fixture-model"))
        .andExpect(jsonPath("$.apiKey").doesNotExist());

    for (long budget : List.of(8192L, 100001L)) {
      var response =
          mvc.perform(
                  post("/api/v1/analysis/preview")
                      .header("Host", "127.0.0.1:8765")
                      .header("X-CheapReview-Token", token)
                      .contentType("application/json")
                      .content(mapper.writeValueAsString(input(Mode.SERVICE_REVIEW, budget))))
              .andExpect(status().is(budget == 8192 ? 200 : 400));
      if (budget == 8192) response.andExpect(jsonPath("$.canExecute").value(true));
      else response.andExpect(jsonPath("$.code").value("INVALID_PREVIEW_REQUEST"));
    }
    verifyNoInteractions(provider);
  }

  private BudgetPreview.Request conversationInput(String id, long version) {
    var original = input(Mode.SERVICE_REVIEW, 8192);
    return new BudgetPreview.Request(
        original.workspaceId(),
        original.snapshotId(),
        original.question(),
        original.mode(),
        original.selectedFileIds(),
        original.inputBudgetTokens(),
        original.modelId(),
        null,
        null,
        original.revision(),
        id,
        version);
  }

  @Test
  void conversationFollowUpUsesEnglishHistoryAndExportImportDoesNotCallProvider() throws Exception {
    var view = conversations.create(workspace);
    var first = request(conversationInput(view.conversationId(), 0));
    assertEquals(
        GenerationDtos.State.SUCCEEDED, completed(generation.start(first).jobId()).state());
    assertEquals(1, conversations.get(view.conversationId()).turns().size());
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    budgets.check(
                        first.previewId(),
                        workspaces.getCapture(workspace, snapshot),
                        first.input()))
            .getStatusCode()
            .value());
    var follow =
        conversationInput(
            view.conversationId(), conversations.get(view.conversationId()).version());
    var preview = budgets.preview(workspaces.getCapture(workspace, snapshot), follow);
    assertEquals(1, preview.historyTurnsIncluded());
    assertTrue(preview.historyTokens() > 0);
    assertTrue(preview.historyTokens() <= follow.inputBudgetTokens() / 4);
    assertEquals(
        preview.counts().tokens(),
        preview.mandatoryPromptTokens()
            + preview.historyTokens()
            + preview.relatedAdditionalTokens());
    assertTrue(preview.promptMaterial().contains("ASSISTANT_ENGLISH_JSON"));
    var start =
        new GenerationDtos.Start(
            preview.previewId(), UUID.randomUUID().toString(), configured.version(), follow);
    assertEquals(
        GenerationDtos.State.SUCCEEDED, completed(generation.start(start).jobId()).state());
    String exported = conversations.export(view.conversationId());
    assertFalse(exported.contains(KEY));
    assertFalse(exported.contains("class Service { public void run() {} }"));
    assertFalse(exported.contains("ANSWER_SCHEMA"));
    var imported =
        conversations.preview(
            new jp.cheapreview.conversation.dto.ConversationDtos.PreviewRequest(
                exported, jp.cheapreview.conversation.dto.ConversationDtos.InputKind.FILE));
    var newView = conversations.importConversation(workspace, imported.importId());
    var newPreview =
        budgets.preview(
            workspaces.getCapture(workspace, snapshot),
            conversationInput(newView.conversationId(), 1));
    assertTrue(newPreview.promptMaterial().contains("USER_IMPORT_TRANSCRIPT (reference only)"));
    assertEquals(2, newPreview.historyTurnsIncluded());
    verify(provider, times(2)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void deletingConversationDuringGenerationCannotCommitLateAnswer() throws Exception {
    var view = conversations.create(workspace);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(provider.generate(any(), any(), anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(3, TimeUnit.SECONDS);
              return reply(ANSWER);
            });
    var started = generation.start(request(conversationInput(view.conversationId(), 0)));
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    conversations.delete(view.conversationId());
    release.countDown();
    var done = completed(started.jobId());
    assertEquals(GenerationDtos.State.FAILED, done.state());
    assertEquals("STALE_CONVERSATION", done.errorCode());
    assertNull(done.result());
    assertThrows(ResponseStatusException.class, () -> conversations.get(view.conversationId()));
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void cancelledOrInvalidAnswerDoesNotAddConversationTurn() throws Exception {
    var view = conversations.create(workspace);
    when(provider.generate(any(), any(), anyLong(), anyLong())).thenReturn(reply("invalid"));
    assertEquals(
        GenerationDtos.State.FAILED,
        completed(generation.start(request(conversationInput(view.conversationId(), 0))).jobId())
            .state());
    assertTrue(conversations.get(view.conversationId()).turns().isEmpty());
    var entered = new CountDownLatch(1);
    when(provider.generate(any(), any(), anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              Thread.sleep(3000);
              return reply(ANSWER);
            });
    var started = generation.start(request(conversationInput(view.conversationId(), 0)));
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    generation.cancel(started.jobId());
    assertEquals(GenerationDtos.State.CANCELLED, completed(started.jobId()).state());
    assertTrue(conversations.get(view.conversationId()).turns().isEmpty());
  }

  @Test
  void importedReferencesAreCheckedAgainstSnapshotWithoutReadingUnscannedFiles() throws Exception {
    var imported =
        conversations.preview(
            new jp.cheapreview.conversation.dto.ConversationDtos.PreviewRequest(
                "ref-1: ../not-scanned.java 1–2行",
                jp.cheapreview.conversation.dto.ConversationDtos.InputKind.PASTE));
    var view = conversations.importConversation(workspace, imported.importId());
    var preview =
        budgets.preview(
            workspaces.getCapture(workspace, snapshot),
            conversationInput(view.conversationId(), 1));
    assertTrue(preview.warnings().stream().anyMatch(warning -> warning.contains("取込TXTのコード参照")));
    assertTrue(
        preview.selectedChunks().stream()
            .allMatch(chunk -> chunk.relativePath().equals("Service.java")));
    verifyNoInteractions(provider);
  }

  /** 実通信せず、説明の日本語化・部分fallback・全失敗を通常生成経路で作る。 */
  private void translatedFixture(String scenario) {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (scenario.equals("failure"))
                throw new jp.cheapreview.translation.service.TranslationFailure(
                    "TRANSLATION_TIMEOUT");
              @SuppressWarnings("unchecked")
              var texts = (List<String>) invocation.getArgument(0);
              return texts.stream()
                  .map(
                      text ->
                          switch (text) {
                            case "Explanation" -> "説明";
                            case "Flow" -> "処理の流れ";
                            case "Evidence" ->
                                scenario.equals("partial") ? "__CR_KEEP_9999__" : "保存済みコードの根拠";
                            default -> text;
                          })
                  .toList();
            })
        .when(translator)
        .translate(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"success", "partial", "failure"})
  void translatedConversationCommitsEnglishOnceAndExportsCurrentDisplay(String scenario)
      throws Exception {
    translatedFixture(scenario);
    var view = conversations.create(workspace);
    var started = generation.start(request(conversationInput(view.conversationId(), 0)));
    var done = completed(started.jobId());
    assertEquals(
        scenario.equals("success")
            ? GenerationDtos.State.SUCCEEDED
            : GenerationDtos.State.TRANSLATION_FAILED,
        done.state());
    var committed = conversations.get(view.conversationId());
    assertEquals(1, committed.turns().size());
    assertEquals(done.result().answerEnglish(), committed.turns().getFirst().answerEnglish());
    assertEquals(done.result().references(), committed.turns().getFirst().references());
    assertEquals(new GenerationDtos.Usage(23L, 12L, null, null, 35L), done.result().usage());
    String exported = conversations.export(view.conversationId());
    if (scenario.equals("failure")) {
      assertTrue(exported.contains("未翻訳"));
      assertTrue(exported.contains("Explanation"));
      assertFalse(done.translation().partialTranslation());
    } else {
      assertTrue(exported.contains("説明"));
      assertEquals(scenario.equals("partial"), exported.contains("未翻訳"));
      if (scenario.equals("partial")) assertTrue(exported.contains("Evidence"));
    }
    var history = conversations.select(view.conversationId(), workspace, 8192, text -> true);
    assertTrue(history.text().contains("Explanation"));
    assertFalse(history.text().contains("処理の流れ"));
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void retranslatingConversationUpdatesTxtAndVersionWithoutChangingEnglishTurnCount()
      throws Exception {
    translatedFixture("failure");
    var view = conversations.create(workspace);
    var done =
        completed(generation.start(request(conversationInput(view.conversationId(), 0))).jobId());
    var original = done.result();
    var before = conversations.get(view.conversationId());
    var oldInput = conversationInput(view.conversationId(), before.version());
    var oldPreview = budgets.preview(workspaces.getCapture(workspace, snapshot), oldInput);
    translatedFixture("success");
    generation.translate(done.jobId());
    var retried = completed(done.jobId());
    var after = conversations.get(view.conversationId());
    assertEquals(original, retried.result());
    assertEquals(1, after.turns().size());
    assertEquals(
        before.turns().getFirst().answerEnglish(), after.turns().getFirst().answerEnglish());
    assertTrue(after.version() > before.version());
    assertTrue(conversations.export(view.conversationId()).contains("保存済みコードの根拠"));
    assertFalse(conversations.export(view.conversationId()).contains("未翻訳"));
    assertEquals(
        "STALE_CONVERSATION",
        assertThrows(
                ResponseStatusException.class,
                () ->
                    budgets.check(
                        oldPreview.previewId(),
                        workspaces.getCapture(workspace, snapshot),
                        oldInput))
            .getReason());
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void cancellingOnlyTranslationKeepsCommittedEnglishAndUntranslatedTxt() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(translator.translate(any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              try {
                release.await(3, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
              }
              return invocation.getArgument(0);
            });
    var view = conversations.create(workspace);
    var started = generation.start(request(conversationInput(view.conversationId(), 0)));
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals(1, conversations.get(view.conversationId()).turns().size());
      var cancelled = generation.cancel(started.jobId());
      assertEquals(GenerationDtos.State.TRANSLATION_FAILED, cancelled.state());
      assertEquals("TRANSLATION_CANCELLED", cancelled.translation().errorCode());
      assertEquals(1, conversations.get(view.conversationId()).turns().size());
      assertTrue(conversations.export(view.conversationId()).contains("未翻訳"));
      assertEquals(new GenerationDtos.Usage(23L, 12L, null, null, 35L), cancelled.result().usage());
    } finally {
      release.countDown();
    }
    completed(started.jobId());
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
  }

  @Test
  void unconfiguredTranslationStillSupportsEnglishConversationAndTxtWithoutTranslatorCalls()
      throws Exception {
    when(translator.configured()).thenReturn(false);
    var view = conversations.create(workspace);
    var done =
        completed(generation.start(request(conversationInput(view.conversationId(), 0))).jobId());
    assertEquals(GenerationDtos.State.TRANSLATION_FAILED, done.state());
    assertEquals("TRANSLATION_NOT_READY", done.translation().errorCode());
    assertEquals(done.result().answerEnglish(), done.translation().displayAnswer());
    var committed = conversations.get(view.conversationId());
    assertEquals(1, committed.turns().size());
    String exported = conversations.export(view.conversationId());
    assertTrue(exported.contains("Explanation"));
    assertTrue(exported.contains("未翻訳"));
    var input = conversationInput(view.conversationId(), committed.version());
    var preview = budgets.preview(workspaces.getCapture(workspace, snapshot), input);
    assertTrue(preview.promptMaterial().contains("ASSISTANT_ENGLISH_JSON"));
    assertEquals(1, preview.historyTurnsIncluded());
    verify(provider, times(1)).generate(any(), any(), anyLong(), anyLong());
    verify(translator, org.mockito.Mockito.never()).translate(any());
    verify(translator, org.mockito.Mockito.never()).translate(any(), any());
  }
}
