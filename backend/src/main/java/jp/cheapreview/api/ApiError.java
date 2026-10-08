package jp.cheapreview.api;

import java.util.List;
import java.util.UUID;

public record ApiError(String code, String message, List<String> details, String requestId) {
  public static ApiError create(String code, String message) {
    return new ApiError(code, message, List.of(), UUID.randomUUID().toString());
  }
}
