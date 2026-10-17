package io.artio.marketplace.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiErrorsTest {
  @Test
  void missingStaticResourceIsNotFoundInsteadOfInternalError() {
    var response =
        new ApiErrors()
            .missingResource(
                new NoResourceFoundException(HttpMethod.GET, "/dev-admin/", "not found"));

    assertEquals(404, response.getStatusCode().value());
    assertEquals("NOT_FOUND", response.getBody().get("code"));
  }
}
