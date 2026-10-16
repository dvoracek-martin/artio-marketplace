package io.artio.marketplace.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PublicationStateTest {
  @Test
  void publishingReservationIsRetryableAndSeparateFromPublicVisibility() {
    assertEquals(
        PublicationState.PUBLISHING,
        PublicationState.VALIDATED.transitionTo(PublicationState.PUBLISHING));
    assertEquals(
        PublicationState.VALIDATED,
        PublicationState.PUBLISHING.transitionTo(PublicationState.VALIDATED));
    assertEquals(
        PublicationState.PUBLISHED,
        PublicationState.PUBLISHING.transitionTo(PublicationState.PUBLISHED));
    assertEquals(false, PublicationState.PUBLISHING.isPubliclyVisible());
    assertThrows(
        IllegalStateException.class,
        () -> PublicationState.PUBLISHED.transitionTo(PublicationState.VALIDATED));
  }
}
