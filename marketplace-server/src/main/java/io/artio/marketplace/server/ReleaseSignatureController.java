package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public final class ReleaseSignatureController {
  private final JdbcTemplate jdbc;
  private final ReleaseDescriptorService descriptors;
  private final String publisherTokenHash;
  private final String signingIngestTokenHash;

  public ReleaseSignatureController(
      JdbcTemplate jdbc,
      ReleaseDescriptorService descriptors,
      @Value("${marketplace.publisher.token-sha256:}") String publisherTokenHash,
      @Value("${marketplace.signing.ingest-token-sha256:}") String signingIngestTokenHash) {
    this.jdbc = jdbc;
    this.descriptors = descriptors;
    this.publisherTokenHash = publisherTokenHash;
    this.signingIngestTokenHash = signingIngestTokenHash;
  }

  @GetMapping("/admin/marketplace/releases/{releaseId}/signing-payload")
  public byte[] signingPayload(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    authenticate(authorization, publisherTokenHash, "publisher");
    return ReleaseDescriptorCanonicalizer.canonicalBytes(
        descriptors.descriptor(releaseId).toString());
  }

  @PostMapping("/admin/marketplace/releases/{releaseId}/signature")
  public java.util.Map<String, Object> submitSignature(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody JsonNode envelope) {
    authenticate(authorization, signingIngestTokenHash, "release signer");
    descriptors.verifyAndStore(releaseId, envelope);
    return java.util.Map.of("releaseId", releaseId, "state", "SIGNED");
  }

  @GetMapping("/marketplace/plugins/{pluginId}/releases/{version}/descriptor")
  public java.util.Map<String, Object> signedDescriptor(
      @PathVariable String pluginId, @PathVariable String version) {
    var rows =
        jdbc.query(
            "SELECT r.release_id::text FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id WHERE r.plugin_id=? AND r.version=? AND r.publication_state='PUBLISHED' AND p.moderation_state='ACTIVE'",
            (rs, n) -> rs.getString(1),
            pluginId,
            version);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Release is unavailable");
    return descriptors.signature(rows.getFirst());
  }

  private static void authenticate(String header, String expectedHash, String role) {
    if (expectedHash == null || expectedHash.isBlank())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, role + " credential is not configured");
    if (header == null || !header.startsWith("Bearer "))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, role + " credential is required");
    String actual;
    try {
      actual =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(header.substring(7).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    if (!MessageDigest.isEqual(
        actual.getBytes(StandardCharsets.US_ASCII),
        expectedHash.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, role + " credential is invalid");
  }
}
