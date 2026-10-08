package jp.cheapreview;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties =
        "cheapreview.connection-token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
@AutoConfigureMockMvc
class LocalApiTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @TempDir Path root;
  private static final String TOKEN = "a".repeat(64);

  @Test
  void healthIsPublicButStatusRequiresConnection() throws Exception {
    mvc.perform(get("/api/v1/health").header("Host", "127.0.0.1:8765"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.protocolVersion").value("1"));
    mvc.perform(get("/api/v1/status").header("Host", "127.0.0.1:8765"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/status")
                .header("Host", "127.0.0.1:8765")
                .header("X-CheapReview-Token", TOKEN))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsHostAndCrossOrigin() throws Exception {
    mvc.perform(get("/api/v1/health").header("Host", "evil.example"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/status")
                .header("Host", "127.0.0.1:8765")
                .header("Origin", "https://evil.example")
                .header("X-CheapReview-Token", TOKEN))
        .andExpect(status().isForbidden());
  }

  @Test
  void folderSelectionThroughScanUsesRealFilesAndClaimsOnlyOnce() throws Exception {
    var register =
        mvc.perform(
                post("/api/v1/bridge/register")
                    .header("Host", "127.0.0.1:8765")
                    .header("X-CheapReview-Token", TOKEN)
                    .contentType("application/json")
                    .content("{\"extensionVersion\":\"0.1.0\",\"windowId\":\"test\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String bridge =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(register.getResponse().getContentAsString())
            .get("bridgeId")
            .asText();
    var created =
        mvc.perform(
                post("/api/v1/bridge/requests")
                    .header("Host", "127.0.0.1:8765")
                    .header("X-CheapReview-Token", TOKEN)
                    .contentType("application/json")
                    .content("{\"kind\":\"FOLDER_PICK\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String requestId =
        mapper.readTree(created.getResponse().getContentAsString()).get("requestId").asText();
    mvc.perform(
            get("/api/v1/bridge/requests/next")
                .param("bridgeId", "wrong")
                .header("Host", "127.0.0.1:8765")
                .header("X-CheapReview-Token", TOKEN))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/bridge/requests/next")
                .param("bridgeId", bridge)
                .header("Host", "127.0.0.1:8765")
                .header("X-CheapReview-Token", TOKEN))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/bridge/requests/next")
                .param("bridgeId", bridge)
                .header("Host", "127.0.0.1:8765")
                .header("X-CheapReview-Token", TOKEN))
        .andExpect(status().isNoContent());
    Files.writeString(root.resolve("Order.java"), "class Order {} // 注文");
    Files.writeString(root.resolve("OrderTest.java"), "class OrderTest {}");
    String resultPath = "/api/v1/bridge/requests/" + requestId + "/result";
    mvc.perform(
            authenticated(post(resultPath))
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "bridgeId",
                            "wrong",
                            "status",
                            "completed",
                            "payload",
                            Map.of("uri", root.toUri().toString())))))
        .andExpect(status().isUnauthorized());
    var completed =
        mvc.perform(
                authenticated(post(resultPath))
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "bridgeId",
                                bridge,
                                "status",
                                "completed",
                                "payload",
                                Map.of("uri", root.toUri().toString())))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("completed"))
            .andReturn();
    String workspaceId =
        mapper
            .readTree(completed.getResponse().getContentAsString())
            .at("/result/workspaceId")
            .asText();
    var started =
        mvc.perform(
                authenticated(post("/api/v1/workspaces/" + workspaceId + "/scan"))
                    .contentType("application/json")
                    .content("{}"))
            .andExpect(status().isAccepted())
            .andReturn();
    String jobId =
        mapper.readTree(started.getResponse().getContentAsString()).get("scanJobId").asText();
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              mvc.perform(authenticated(get("/api/v1/jobs/" + jobId)))
                  .andExpect(jsonPath("$.state").value("SUCCEEDED"));
            });
    mvc.perform(authenticated(get("/api/v1/workspaces/" + workspaceId + "/files")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files.length()").value(1))
        .andExpect(jsonPath("$.files[0].relativePath").value("Order.java"));
  }

  private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
    return request.header("Host", "127.0.0.1:8765").header("X-CheapReview-Token", TOKEN);
  }
}
