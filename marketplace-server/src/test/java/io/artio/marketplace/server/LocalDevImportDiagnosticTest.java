package io.artio.marketplace.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class LocalDevImportDiagnosticTest {
  @Test
  void localImportDiagnosticsRedactCredentialsAndStayBounded() {
    String diagnostic =
        LocalDevImportController.safeDiagnostic(
            new IOException(
                "Skopeo rejected archive: Authorization: Basic dXNlcjpwYXNzd29yZA== "
                    + "password=super-secret token:bearer-value "
                    + "https://operator:secret@example.invalid/registry "
                    + "x".repeat(800)));

    assertTrue(diagnostic.startsWith("IOException: Skopeo rejected archive:"));
    assertFalse(diagnostic.contains("dXNlcjpwYXNzd29yZA=="));
    assertFalse(diagnostic.contains("super-secret"));
    assertFalse(diagnostic.contains("bearer-value"));
    assertFalse(diagnostic.contains("operator:secret"));
    assertTrue(diagnostic.length() <= 512);
  }
}
