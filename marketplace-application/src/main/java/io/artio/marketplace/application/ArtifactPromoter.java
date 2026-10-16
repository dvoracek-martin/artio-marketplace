package io.artio.marketplace.application;

/** Copies an approved, digest-pinned image bundle from private staging into the public registry. */
public interface ArtifactPromoter {
  void promote(String manifestJson);
}
