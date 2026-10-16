package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds immutable descriptors from published release rows and validates independent signatures.
 */
@Component
public final class ReleaseDescriptorService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Map<String, String> trustedKeys;
  private final SecurityAdvisoryOperations advisoryOperations;

  public ReleaseDescriptorService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      SecurityAdvisoryOperations advisoryOperations,
      @Value("${marketplace.signing.trusted-keys:}") String trustedKeys) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.advisoryOperations = advisoryOperations;
    this.trustedKeys = parseTrustedKeys(trustedKeys);
  }

  public JsonNode descriptor(String releaseId) {
    var rows =
        jdbc.query(
            "SELECT r.plugin_id,r.version,r.manifest_document,r.publication_state FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id WHERE r.release_id=?::uuid AND p.moderation_state='ACTIVE'",
            (rs, n) ->
                new ReleaseRow(
                    rs.getString("plugin_id"),
                    rs.getString("version"),
                    rs.getString("manifest_document"),
                    rs.getString("publication_state")),
            releaseId);
    if (rows.isEmpty() || !"PUBLISHED".equals(rows.getFirst().state()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only a completely published release can be signed");
    try {
      JsonNode manifest = mapper.readTree(rows.getFirst().manifest());
      ObjectNode descriptor = mapper.createObjectNode();
      descriptor.put("schemaVersion", 1);
      descriptor.put("pluginId", rows.getFirst().pluginId());
      descriptor.put("version", rows.getFirst().version());
      descriptor.put(
          "manifestDigest",
          "sha256:"
              + sha(
                  ReleaseDescriptorCanonicalizer.canonicalBytes(
                      mapper.writeValueAsString(manifest))));
      descriptor.set("manifest", manifest);
      ArrayNode images = descriptor.putArray("images");
      JsonNode runtimeManifest =
          manifest.has("packageManifest") ? manifest.path("packageManifest") : manifest;
      appendImage(images, "MAIN", runtimeManifest.path("image").asText());
      JsonNode services = runtimeManifest.path("services");
      if (services.isArray()) {
        for (JsonNode service : services) {
          String id = service.path("id").asText();
          appendImage(images, "SIDECAR:" + id, service.path("image").asText());
        }
      }
      return descriptor;
    } catch (Exception failure) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Published manifest cannot form a release descriptor",
          failure);
    }
  }

  public Map<String, Object> signature(String releaseId) {
    var rows =
        jdbc.query(
            "SELECT s.descriptor_document,s.key_id,s.signature_base64url FROM release_signatures s JOIN marketplace_releases r ON r.release_id=s.release_id JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id WHERE s.release_id=?::uuid AND r.publication_state='PUBLISHED' AND p.moderation_state='ACTIVE' ORDER BY s.signed_at DESC LIMIT 1",
            (rs, n) ->
                Map.<String, Object>of(
                    "descriptor", parse(rs.getString("descriptor_document")),
                    "signature",
                        Map.of(
                            "algorithm",
                            "Ed25519",
                            "keyId",
                            rs.getString("key_id"),
                            "value",
                            rs.getString("signature_base64url"))),
            releaseId);
    if (rows.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Signed release descriptor is not available");
    return rows.getFirst();
  }

  public void verifyAndStore(String releaseId, JsonNode envelope) {
    JsonNode descriptor = envelope.path("descriptor");
    JsonNode signature = envelope.path("signature");
    if (!envelope.isObject()
        || envelope.size() != 2
        || !descriptor.isObject()
        || !signature.isObject()
        || signature.size() != 3
        || !"Ed25519".equals(signature.path("algorithm").asText()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Signed descriptor envelope is invalid");
    JsonNode expected = descriptor(releaseId);
    byte[] canonicalExpected = canonical(expected);
    byte[] canonicalSubmitted = canonical(descriptor);
    if (!MessageDigest.isEqual(canonicalExpected, canonicalSubmitted))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Signature payload does not match the immutable published release");
    String keyId = signature.path("keyId").asText();
    String encodedKey = trustedKeys.get(keyId);
    if (keyId.isBlank() || encodedKey == null)
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Signing key is not trusted by Marketplace");
    String encodedSignature = signature.path("value").asText();
    try {
      byte[] signatureBytes = Base64.getUrlDecoder().decode(encodedSignature);
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(
          KeyFactory.getInstance("Ed25519")
              .generatePublic(new X509EncodedKeySpec(Base64.getUrlDecoder().decode(encodedKey))));
      verifier.update(canonicalExpected);
      if (!verifier.verify(signatureBytes))
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Release signature is invalid");
      String document = new String(canonicalExpected, StandardCharsets.UTF_8);
      int inserted =
          jdbc.update(
              "INSERT INTO release_signatures(release_id,key_id,descriptor_sha256,descriptor_document,signature_base64url) VALUES (?::uuid,?,?,?,?) ON CONFLICT(release_id,key_id) DO NOTHING",
              releaseId,
              keyId,
              sha(canonicalExpected),
              document,
              encodedSignature);
      var stored =
          jdbc.queryForObject(
              "SELECT descriptor_sha256 FROM release_signatures WHERE release_id=?::uuid AND key_id=?",
              String.class,
              releaseId,
              keyId);
      if (!sha(canonicalExpected).equals(stored))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "A different immutable signature already exists for this key");
      if (inserted == 1) advisoryOperations.touchFeed();
    } catch (ResponseStatusException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Release signature encoding is invalid", failure);
    }
  }

  private JsonNode parse(String value) {
    try {
      return mapper.readTree(value);
    } catch (Exception failure) {
      throw new IllegalStateException("Stored signed descriptor is invalid", failure);
    }
  }

  private byte[] canonical(JsonNode value) {
    try {
      return ReleaseDescriptorCanonicalizer.canonicalBytes(mapper.writeValueAsString(value));
    } catch (Exception failure) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Descriptor JSON is invalid", failure);
    }
  }

  private static void appendImage(ArrayNode images, String role, String reference) {
    int at = reference.lastIndexOf("@sha256:");
    if (at <= 0 || !reference.substring(at + 1).matches("sha256:[a-f0-9]{64}"))
      throw new IllegalArgumentException("Published image reference is not digest-pinned");
    String repository = reference.substring(0, at);
    if (!repository.matches("[A-Za-z0-9.-]+(?::[0-9]{1,5})?/public/plugins/[A-Za-z0-9._/-]+"))
      throw new IllegalArgumentException(
          "Published image is outside the public plugin registry namespace");
    ObjectNode item = images.addObject();
    item.put("role", role);
    item.put("repository", repository);
    item.put("digest", reference.substring(at + 1));
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static Map<String, String> parseTrustedKeys(String configured) {
    if (configured == null || configured.isBlank()) return Map.of();
    var keys = new java.util.LinkedHashMap<String, String>();
    for (String entry : configured.split(",")) {
      String[] pair = entry.trim().split("=", 2);
      if (pair.length != 2
          || !pair[0].matches("[A-Za-z0-9._-]{1,120}")
          || pair[1].isBlank()
          || keys.putIfAbsent(pair[0], pair[1]) != null)
        throw new IllegalArgumentException(
            "marketplace.signing.trusted-keys must be unique keyId=base64url entries");
      try {
        Base64.getUrlDecoder().decode(pair[1]);
      } catch (IllegalArgumentException failure) {
        throw new IllegalArgumentException("Marketplace trusted key must use base64url", failure);
      }
    }
    return Map.copyOf(keys);
  }

  private record ReleaseRow(String pluginId, String version, String manifest, String state) {}
}
