package io.artio.marketplace.server;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
    var status = HttpStatus.valueOf(e.getStatusCode().value());
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                status.name(),
                "message",
                e.getReason() == null ? status.getReasonPhrase() : e.getReason(),
                "requestId",
                UUID.randomUUID().toString()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "INVALID_REQUEST",
                "message",
                e.getMessage(),
                "requestId",
                UUID.randomUUID().toString()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    String requestId = UUID.randomUUID().toString();
    LOGGER.error("Unhandled API error requestId={} type={}", requestId, e.getClass().getName(), e);
    return ResponseEntity.internalServerError()
        .body(
            Map.of(
                "code",
                "INTERNAL_ERROR",
                "message",
                "Request could not be completed",
                "requestId",
                requestId));
  }
}
