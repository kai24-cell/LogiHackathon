package jp.cheapreview.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiError> status(ResponseStatusException exception) {
    String code = exception.getReason() == null ? "INVALID_REQUEST" : exception.getReason();
    return ResponseEntity.status(exception.getStatusCode())
        .body(ApiError.create(code, "操作を確認して再試行してください"));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiError> invalid(Exception exception) {
    return ResponseEntity.badRequest().body(ApiError.create("INVALID_REQUEST", "入力内容を確認してください"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception exception) {
    // Exceptions may contain local paths or request bodies. Never expose their text.
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError.create("INTERNAL_ERROR", "処理に失敗しました。再試行してください"));
  }
}
