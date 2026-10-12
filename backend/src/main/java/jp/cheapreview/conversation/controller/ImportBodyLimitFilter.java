package jp.cheapreview.conversation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import jp.cheapreview.api.ApiError;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** JSONエスケープ後の取込本文も2MiBで制限し、Jackson読込前に厳密UTF-8を確認する。 */
@Component
public class ImportBodyLimitFilter extends OncePerRequestFilter {
  private static final int MAX_BODY = 2_097_152;
  private final ObjectMapper mapper;

  public ImportBodyLimitFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().equals("/api/v1/conversation-imports/preview")
        || !request.getMethod().equals("POST");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    // Content-Lengthを信用せずchunked本文も最大+1byteだけ読む。値をログへ出さない。
    byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
    if (body.length > MAX_BODY) {
      reject(response, 413, "IMPORT_TOO_LARGE");
      return;
    }
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(body));
    } catch (java.nio.charset.CharacterCodingException failure) {
      reject(response, 400, "INVALID_TRANSCRIPT");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              @Override
              public int read() {
                return source.read();
              }

              @Override
              public boolean isFinished() {
                return source.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Synchronous import only");
              }
            };
          }
        },
        response);
  }

  private void reject(HttpServletResponse response, int status, String code) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    mapper.writeValue(response.getOutputStream(), ApiError.create(code, "取込形式・容量を確認してください"));
  }
}
