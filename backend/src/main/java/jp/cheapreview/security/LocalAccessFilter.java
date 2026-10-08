package jp.cheapreview.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import jp.cheapreview.api.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalAccessFilter extends OncePerRequestFilter {
  private static final int MIN_TOKEN_LENGTH = 32;
  private final ObjectMapper mapper;
  private final byte[] token;
  private final String host;
  private final Set<String> origins;

  public LocalAccessFilter(
      @Value("${cheapreview.connection-token}") String token,
      @Value("${server.port}") int port,
      @Value("${cheapreview.dev-origin:}") String devOrigin,
      ObjectMapper mapper) {
    if (token.length() < MIN_TOKEN_LENGTH) {
      throw new IllegalArgumentException("Connection token is required");
    }
    this.mapper = mapper;
    this.token = token.getBytes(StandardCharsets.UTF_8);
    host = "127.0.0.1:" + port;
    origins =
        devOrigin.isBlank()
            ? Set.of("http://" + host)
            : Set.copyOf(java.util.List.of("http://" + host, devOrigin));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    if (!host.equals(request.getHeader("Host")) || (origin != null && !origins.contains(origin))) {
      reject(response, HttpServletResponse.SC_FORBIDDEN, "INVALID_ORIGIN");
      return;
    }
    if (request.getRequestURI().startsWith("/api/")
        && !request.getRequestURI().equals("/api/v1/health")) {
      String supplied = request.getHeader("X-CheapReview-Token");
      if (supplied == null
          || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
        reject(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_CONNECTION");
        return;
      }
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    chain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, int status, String code) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    mapper.writeValue(response.getOutputStream(), ApiError.create(code, "接続を確認してください"));
  }
}
