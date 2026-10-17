package io.artio.marketplace.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalDevImportProcessTest {
  @Test
  void directsAllSkopeoTemporaryFilesIntoTheWritablePerJobQuarantineDirectory() {
    var builder = new ProcessBuilder("/usr/bin/skopeo", "copy");
    Path quarantineScratch = Path.of("/var/lib/artio-marketplace/quarantine/job/skopeo-tmp");

    LocalDevImportController.configureSkopeoTemporaryDirectory(builder, quarantineScratch);

    String expected = quarantineScratch.toAbsolutePath().normalize().toString();
    assertEquals(expected, builder.environment().get("TMPDIR"));
    assertEquals(expected, builder.environment().get("TMP"));
    assertEquals(expected, builder.environment().get("TEMP"));

    var originalCommand = java.util.List.of("/usr/bin/skopeo", "copy", "source", "destination");
    var configuredCommand =
        LocalDevImportController.withSkopeoTemporaryDirectory(originalCommand, quarantineScratch);
    assertEquals("/usr/bin/skopeo", configuredCommand.getFirst());
    assertEquals("--tmpdir", configuredCommand.get(1));
    assertEquals(expected, configuredCommand.get(2));
    assertEquals("copy", configuredCommand.get(3));
    assertNotSame(originalCommand, configuredCommand);
    assertEquals(
        java.util.List.of("/usr/bin/skopeo", "copy", "source", "destination"), originalCommand);
  }
}
