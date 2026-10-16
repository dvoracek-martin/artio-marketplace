package io.artio.marketplace.domain;

import java.util.EnumSet;
import java.util.Map;

/** Marketplace publication workflow. These states are not Artio Core lifecycle states. */
public enum PublicationState {
  DRAFT,
  VALIDATED,
  PUBLISHING,
  PUBLISHED,
  UNLISTED,
  REVOKED;

  private static final Map<PublicationState, EnumSet<PublicationState>> TRANSITIONS =
      Map.of(
          DRAFT, EnumSet.of(VALIDATED),
          VALIDATED, EnumSet.of(DRAFT, PUBLISHING),
          PUBLISHING, EnumSet.of(VALIDATED, PUBLISHED),
          PUBLISHED, EnumSet.of(UNLISTED, REVOKED),
          UNLISTED, EnumSet.of(PUBLISHED, REVOKED),
          REVOKED, EnumSet.noneOf(PublicationState.class));

  public PublicationState transitionTo(PublicationState next) {
    if (!TRANSITIONS.get(this).contains(next)) {
      throw new IllegalStateException("Invalid publication transition: " + this + " -> " + next);
    }
    return next;
  }

  public boolean isPubliclyVisible() {
    return this == PUBLISHED;
  }
}
