package io.artio.marketplace.domain;

import java.util.EnumSet;
import java.util.Map;

/** Plugin-level marketplace moderation, independent of each release's publication state. */
public enum ModerationState {
  ACTIVE,
  SUSPENDED,
  BANNED,
  REVIEW_REQUIRED;

  private static final Map<ModerationState, EnumSet<ModerationState>> TRANSITIONS =
      Map.of(
          ACTIVE, EnumSet.of(SUSPENDED, BANNED),
          SUSPENDED, EnumSet.of(ACTIVE, BANNED),
          BANNED, EnumSet.of(ACTIVE),
          REVIEW_REQUIRED, EnumSet.of(ACTIVE, SUSPENDED, BANNED));

  public ModerationState transitionTo(ModerationState next) {
    if (!TRANSITIONS.get(this).contains(next))
      throw new IllegalStateException("Invalid moderation transition: " + this + " -> " + next);
    return next;
  }

  public boolean isPubliclyDiscoverable() {
    return this == ACTIVE;
  }
}
