package io.artio.extensions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.erdtman.jcs.JsonCanonicalizer;

/** RFC 8785 canonical bytes shared by Marketplace signing and Core verification. */
public final class ReleaseDescriptorCanonicalizer {
  private ReleaseDescriptorCanonicalizer() {}

  public static byte[] canonicalBytes(String json) {
    if (json == null || json.isBlank() || json.stripLeading().charAt(0) != '{')
      throw new IllegalArgumentException("Release descriptor must be a JSON object");
    try {
      return new JsonCanonicalizer(json).getEncodedString().getBytes(StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("Release descriptor cannot be canonicalized", failure);
    }
  }

  public static boolean isCanonical(byte[] bytes) {
    if (bytes == null || bytes.length == 0) return false;
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (text.isBlank() || text.stripLeading().charAt(0) != '{') return false;
      return java.util.Arrays.equals(bytes, canonicalBytes(text));
    } catch (IOException | RuntimeException failure) {
      return false;
    }
  }
}
