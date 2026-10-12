package jp.cheapreview.conversation.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ImportBodyLimitFilterTest {
  final ImportBodyLimitFilter filter = new ImportBodyLimitFilter(new ObjectMapper());

  MockHttpServletRequest request(byte[] bytes) {
    var request = new MockHttpServletRequest("POST", "/api/v1/conversation-imports/preview");
    request.setContent(bytes);
    return request;
  }

  @Test
  void chunkedSizedContentIsBoundedBeforeJsonParsing() throws Exception {
    var request = request(new byte[2_097_153]);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertEquals(413, response.getStatus());
    assertNull(chain.getRequest());
  }

  @Test
  void malformedUtf8IsRejectedWithoutReplacement() throws Exception {
    var request = request(new byte[] {(byte) 0xc3, 0x28});
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertEquals(400, response.getStatus());
    assertNull(chain.getRequest());
  }

  @Test
  void validUtf8JsonRemainsExactlyTheSameForController() throws Exception {
    byte[] bytes = "{\"text\":\"日本語😀\",\"inputKind\":\"PASTE\"}".getBytes(StandardCharsets.UTF_8);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request(bytes), response, chain);
    assertArrayEquals(bytes, chain.getRequest().getInputStream().readAllBytes());
  }
}
