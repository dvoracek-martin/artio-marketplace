package io.artio.marketplace.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModerationStateTest {
  @Test
  void moderationTransitionsAreIndependentAndExplicit() {
    assertEquals(
        ModerationState.SUSPENDED, ModerationState.ACTIVE.transitionTo(ModerationState.SUSPENDED));
    assertEquals(
        ModerationState.ACTIVE,
        ModerationState.REVIEW_REQUIRED.transitionTo(ModerationState.ACTIVE));
    assertThrows(
        IllegalStateException.class,
        () -> ModerationState.ACTIVE.transitionTo(ModerationState.ACTIVE));
  }
}
