package io.artio.marketplace.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class PublisherControllerCredentialTest {
  @Test
  void refusesAConfiguredTokenSharedBetweenPublisherAndAdminRoles() {
    assertThatThrownBy(() -> controller("same", "same", "revocation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be unique");
  }

  @Test
  void acceptsThreeDistinctRoleCredentials() {
    assertThatCode(() -> controller("publisher", "admin", "revocation")).doesNotThrowAnyException();
  }

  private PublisherController controller(String publisher, String admin, String revocation) {
    return new PublisherController(
        null,
        new ObjectMapper(),
        null,
        null,
        null,
        null,
        mock(PlatformTransactionManager.class),
        null,
        publisher,
        admin,
        revocation);
  }
}
