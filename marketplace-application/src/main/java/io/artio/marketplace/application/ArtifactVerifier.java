package io.artio.marketplace.application;

import java.util.List;

/** Port to verify remote OCI descriptors without involving the customer Docker daemon. */
public interface ArtifactVerifier {
  List<String> verify(String registryHost, String manifestJson);
}
