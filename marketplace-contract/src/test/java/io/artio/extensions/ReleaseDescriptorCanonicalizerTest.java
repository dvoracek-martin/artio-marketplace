package io.artio.extensions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReleaseDescriptorCanonicalizerTest {
  @Test
  void canonicalizesPropertiesAndNumbersAccordingToJcs() {
    byte[] canonical =
        ReleaseDescriptorCanonicalizer.canonicalBytes("{\"z\":1.0,\"a\":{\"b\":2,\"a\":true}}");

    assertArrayEquals(
        "{\"a\":{\"a\":true,\"b\":2},\"z\":1}".getBytes(StandardCharsets.UTF_8), canonical);
  }

  @Test
  void acceptsOnlyByteExactCanonicalJson() {
    assertTrue(
        ReleaseDescriptorCanonicalizer.isCanonical(
            "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8)));
    assertFalse(
        ReleaseDescriptorCanonicalizer.isCanonical(
            "{ \"b\":2,\"a\":1 }".getBytes(StandardCharsets.UTF_8)));
  }
}
