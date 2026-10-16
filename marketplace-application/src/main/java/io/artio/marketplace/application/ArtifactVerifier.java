package io.artio.marketplace.application;

import java.util.List;

/** Port to verify remote OCI descriptors without involving the customer Docker daemon. */
public interface ArtifactVerifier {
  /** Verifies the private staged copies represented by the canonical public manifest references. */
  List<String> verify(String registryHost, String manifestJson);

  /** Verifies the final public copies; custom implementations may use the same test fixture. */
  default List<String> verifyPublished(String registryHost, String manifestJson) {
    return verify(registryHost, manifestJson);
  }
}
