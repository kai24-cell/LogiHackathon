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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties =
        "cheapreview.connection-token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
@AutoConfigureMockMvc
class GenerationIntegrationTest {
  @Autowired MockMvc mvc;
  private static final String KEY = "test-only-key-000";
  private static final String ANSWER =
      "{\"summary\":\"Explanation\",\"sections\":[{\"title\":\"Flow\",\"body\":\"Evidence\",\"code\":\"\",\"referenceIds\":[\"ref-1\",\"unknown\"]}],\"findings\":[],\"limitations\":[]}";
  @Autowired WorkspaceService workspaces;
  @Autowired BudgetPreviewService budgets;
  @Autowired GenerationService generation;
  @Autowired GenerationSettings settings;
  @Autowired AnswerValidator validator;
  @Autowired ObjectMapper mapper;
  @MockitoBean GeminiClient provider;
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
          && job.state() != GenerationDtos.State.GENERATING) {
        Thread.sleep(20);
        return job;
      }
      if (System.nanoTime() > deadline) fail("Generation timeout");
      Thread.sleep(10);
    } while (true);
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
}
