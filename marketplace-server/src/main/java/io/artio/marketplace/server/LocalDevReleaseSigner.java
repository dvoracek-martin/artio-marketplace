package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local-development signer. Private key is mounted read-only and never crosses the browser API. */
@Component
@Profile("marketplace-local-dev")
public final class LocalDevReleaseSigner {
  private final ReleaseDescriptorService descriptors;
  private final ObjectMapper mapper;
  private final Path privateKey;
  private final String keyId;

  public LocalDevReleaseSigner(
      ReleaseDescriptorService descriptors,
      ObjectMapper mapper,
      @Value("${MARKETPLACE_LOCAL_SIGNING_KEY_FILE:}") String privateKey,
      @Value("${MARKETPLACE_SIGNING_KEY_ID:}") String keyId) {
    this.descriptors = descriptors;
    this.mapper = mapper;
    this.privateKey = privateKey == null || privateKey.isBlank() ? null : Path.of(privateKey);
    this.keyId = keyId;
  }

  public void signPublishedRelease(String releaseId) {
    try {
      if (privateKey == null
          || !Files.isRegularFile(privateKey)
          || keyId == null
          || !keyId.matches("[A-Za-z0-9._-]{1,120}"))
        throw new IllegalStateException("Local signing key is not configured");
      String pem = Files.readString(privateKey);
      String base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      byte[] key = Base64.getDecoder().decode(base64);
      var signer = Signature.getInstance("Ed25519");
      signer.initSign(
          KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(key)));
      var descriptor = descriptors.descriptor(releaseId);
      signer.update(
          ReleaseDescriptorCanonicalizer.canonicalBytes(mapper.writeValueAsString(descriptor)));
      String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
      ObjectNode envelope = mapper.createObjectNode();
      envelope.set("descriptor", descriptor);
      ObjectNode identity = envelope.putObject("signature");
      identity.put("algorithm", "Ed25519");
      identity.put("keyId", keyId);
      identity.put("value", signature);
      // The normal verifier checks the signature against the trusted public key before storage.
      descriptors.verifyAndStore(releaseId, envelope);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Local published-release signing failed", failure);
    }
  }
}
