package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Append-only publisher of Core-effective support and exact OCI digest policy. */
@Service
public class SecurityAdvisoryOperations {
  private static final Set<String> LOCALES = Set.of("en", "de", "fr", "it");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public SecurityAdvisoryOperations(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional
  public int setSupport(
      String releaseId,
      String supportStatus,
      String reasonCode,
      Map<String, String> explanations,
      String actor) {
    if (!Set.of("SUPPORTED", "UNSUPPORTED", "REMOVED").contains(supportStatus))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid support status");
    return append(releaseId, supportStatus, null, null, "INFO", reasonCode, explanations, actor);
  }

  @Transactional
  public int banRelease(
      String releaseId,
      String reasonCode,
      String severity,
      Map<String, String> explanations,
      String actor) {
    validateBanSeverity(severity);
    return append(releaseId, null, "BANNED", null, severity, reasonCode, explanations, actor);
  }

  @Transactional
  public int banImage(
      String releaseId,
      String digest,
      String reasonCode,
      String severity,
      Map<String, String> explanations,
      String actor) {
    validateBanSeverity(severity);
    if (digest == null || !digest.matches("sha256:[a-f0-9]{64}"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "An exact sha256 image digest is required");
    return append(
        releaseId, null, "BANNED", List.of(digest), severity, reasonCode, explanations, actor);
  }

  @Transactional
  public int banPlugin(
      String pluginId,
      String reasonCode,
      String severity,
      Map<String, String> explanations,
      String actor) {
    validateBanSeverity(severity);
    List<String> releaseIds =
        jdbc.query(
            "SELECT DISTINCT s.release_id::text FROM release_signatures s JOIN marketplace_releases r ON r.release_id=s.release_id WHERE r.plugin_id=? ORDER BY s.release_id::text",
            (rs, row) -> rs.getString(1),
            pluginId);
    if (releaseIds.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "No signed releases exist for this plugin");
    int updated = 0;
    for (String releaseId : releaseIds)
      updated += append(releaseId, null, "BANNED", null, severity, reasonCode, explanations, actor);
    return updated;
  }

  @Transactional
  public int recordPublicationRemoval(
      String releaseId,
      String supportStatus,
      String securityStatus,
      String reasonCode,
      String severity,
      Map<String, String> explanations,
      String actor) {
    return append(
        releaseId, supportStatus, securityStatus, null, severity, reasonCode, explanations, actor);
  }

  @Transactional
  public void touchFeed() {
    jdbc.update(
        "UPDATE marketplace_advisory_feed_state SET sequence=sequence+1,issued_at=now() WHERE singleton_id=true");
  }

  private int append(
      String releaseId,
      String supportStatus,
      String securityStatus,
      List<String> banOverride,
      String severity,
      String reasonCode,
      Map<String, String> explanations,
      String actor) {
    validateText(severity, reasonCode, explanations);
    ReleaseIdentity identity =
        jdbc.query(
            "SELECT plugin_id,version,manifest_document,publication_state FROM marketplace_releases WHERE release_id=?::uuid",
            rs ->
                rs.next()
                    ? new ReleaseIdentity(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                    : null,
            releaseId);
    if (identity == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Release was not found");
    String previousSupport =
        "PUBLISHED".equals(identity.publicationState()) ? "SUPPORTED" : "REMOVED";
    String previousSecurity = "ALLOWED";
    List<String> previousBans = List.of();
    var previous =
        jdbc.query(
            "SELECT support_status,security_status,banned_image_digests::text FROM marketplace_release_advisory_events "
                + "WHERE release_id=?::uuid ORDER BY sequence DESC LIMIT 1",
            rs ->
                rs.next()
                    ? new Previous(rs.getString(1), rs.getString(2), readStrings(rs.getString(3)))
                    : null,
            releaseId);
    if (previous != null) {
      previousSupport = previous.supportStatus();
      previousSecurity = previous.securityStatus();
      previousBans = previous.bannedImageDigests();
    }
    JsonNode manifest;
    try {
      manifest = mapper.readTree(identity.manifest());
    } catch (Exception failure) {
      throw new IllegalStateException("Stored release manifest is invalid", failure);
    }
    JsonNode runtimeManifest =
        manifest.has("packageManifest") ? manifest.path("packageManifest") : manifest;
    List<String> allDigests = imageDigests(runtimeManifest);
    if (allDigests.isEmpty())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Release has no digest-pinned images");
    List<String> banned = new ArrayList<>(previousBans);
    if (banOverride != null) banned.addAll(banOverride);
    if ("BANNED".equals(securityStatus) && banOverride == null) banned = allDigests;
    if ("BANNED".equals(securityStatus) && banned.isEmpty()) banned = allDigests;
    if (!allDigests.containsAll(banned))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Ban digest is not declared by the selected release");
    String nextSupport = supportStatus == null ? previousSupport : supportStatus;
    String nextSecurity = securityStatus == null ? previousSecurity : securityStatus;
    String descriptorDigest =
        jdbc.query(
            "SELECT descriptor_sha256 FROM release_signatures WHERE release_id=?::uuid ORDER BY signed_at DESC LIMIT 1",
            rs -> rs.next() ? rs.getString(1).trim() : null,
            releaseId);
    if (descriptorDigest == null)
      descriptorDigest = sha(ReleaseDescriptorCanonicalizer.canonicalBytes(identity.manifest()));
    Long sequence =
        jdbc.queryForObject(
            "UPDATE marketplace_advisory_feed_state SET sequence=sequence+1,issued_at=now() "
                + "WHERE singleton_id=true RETURNING sequence",
            Long.class);
    jdbc.update(
        "INSERT INTO marketplace_release_advisory_events(sequence,advisory_id,release_id,plugin_id,version,release_descriptor_digest,"
            + "image_digests,support_status,security_status,banned_image_digests,severity,reason_code,explanations,actor) "
            + "VALUES (?,?,?::uuid,?,?,?,?::jsonb,?,?,?::jsonb,?,?,?::jsonb,?)",
        sequence,
        UUID.randomUUID(),
        releaseId,
        identity.pluginId(),
        identity.version(),
        descriptorDigest,
        json(allDigests),
        nextSupport,
        nextSecurity,
        json(banned.stream().distinct().sorted().toList()),
        severity,
        reasonCode,
        json(explanations),
        actor);
    return 1;
  }

  private List<String> imageDigests(JsonNode manifest) {
    List<String> result = new ArrayList<>();
    addDigest(result, manifest.path("image").asText(null));
    JsonNode services = manifest.path("services");
    if (services.isArray())
      for (JsonNode service : services) addDigest(result, service.path("image").asText(null));
    return result.stream().distinct().sorted().toList();
  }

  private static void addDigest(List<String> result, String reference) {
    if (reference == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Release image reference is missing");
    int separator = reference.lastIndexOf("@sha256:");
    if (separator <= 0 || !reference.substring(separator + 1).matches("sha256:[a-f0-9]{64}"))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Release image is not pinned to an exact sha256 digest");
    result.add(reference.substring(separator + 1));
  }

  private void validateText(String severity, String reasonCode, Map<String, String> explanations) {
    if (severity == null || !Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL").contains(severity))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid advisory severity");
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,47}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid advisory reason code");
    if (explanations == null
        || !LOCALES.containsAll(explanations.keySet())
        || !explanations.containsKey("en")
        || explanations.values().stream()
            .anyMatch(
                value ->
                    value == null
                        || value.isBlank()
                        || value.length() > 500
                        || value.contains("<")
                        || value.contains(">")))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Advisory explanations require bounded plain text with an English value");
  }

  private static void validateBanSeverity(String severity) {
    if (severity == null || !Set.of("HIGH", "CRITICAL").contains(severity))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Security bans require HIGH or CRITICAL severity");
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalArgumentException("Could not serialize advisory", failure);
    }
  }

  private List<String> readStrings(String json) {
    try {
      return mapper.readValue(
          json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception failure) {
      throw new IllegalStateException("Stored advisory digest list is invalid", failure);
    }
  }

  private static String sha(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private record ReleaseIdentity(
      String pluginId, String version, String manifest, String publicationState) {}

  private record Previous(
      String supportStatus, String securityStatus, List<String> bannedImageDigests) {}
}
