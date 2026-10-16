package io.artio.marketplace.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Produces a cacheable, Ed25519-signed snapshot; the private advisory key is independent of API
 * tokens.
 */
@Service
public class SignedAdvisoryFeedService {
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> TEXT_MAP = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final String keyId;
  private final String privateKeyBase64;
  private final Duration ttl;

  public SignedAdvisoryFeedService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      @Value("${marketplace.advisory.signing.key-id:}") String keyId,
      @Value("${marketplace.advisory.signing.private-key-base64:}") String privateKeyBase64,
      @Value("${marketplace.advisory.feed-ttl:PT24H}") Duration ttl) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.keyId = keyId;
    this.privateKeyBase64 = privateKeyBase64;
    this.ttl = ttl;
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(7)) > 0)
      throw new IllegalArgumentException(
          "Marketplace advisory feed TTL must be between one second and seven days");
  }

  public Map<String, Object> signedSnapshot() {
    if (keyId == null
        || !keyId.matches("[A-Za-z0-9._-]{1,120}")
        || privateKeyBase64 == null
        || privateKeyBase64.isBlank())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Marketplace advisory signing is not configured");
    FeedState state =
        jdbc.query(
            "SELECT sequence,issued_at FROM marketplace_advisory_feed_state WHERE singleton_id=true",
            rs ->
                rs.next()
                    ? new FeedState(rs.getLong(1), rs.getObject(2, OffsetDateTime.class))
                    : null);
    if (state == null)
      throw new IllegalStateException("Marketplace advisory sequence state is missing");
    List<Entry> entries =
        jdbc.query(
            """
        WITH latest AS (
          SELECT DISTINCT ON (release_id) release_id,advisory_id,release_descriptor_digest,image_digests::text,
                 support_status,security_status,banned_image_digests::text,severity,reason_code,explanations::text,created_at
          FROM marketplace_release_advisory_events ORDER BY release_id,sequence DESC
        ), signed AS (
          SELECT DISTINCT ON (r.release_id) r.release_id,r.plugin_id,r.version,r.publication_state,
                 s.descriptor_sha256,s.descriptor_document
          FROM marketplace_releases r JOIN release_signatures s ON s.release_id=r.release_id
          ORDER BY r.release_id,s.signed_at DESC
        )
        SELECT s.release_id::text,s.plugin_id,s.version,s.descriptor_sha256,
               COALESCE(l.image_digests, (SELECT jsonb_agg(DISTINCT image->>'digest' ORDER BY image->>'digest')::text
                   FROM jsonb_array_elements(s.descriptor_document::jsonb->'images') image)),
               COALESCE(l.support_status,CASE WHEN s.publication_state='PUBLISHED' THEN 'SUPPORTED' ELSE 'REMOVED' END),
               COALESCE(l.security_status,'ALLOWED'),COALESCE(l.banned_image_digests,'[]'),
               COALESCE(l.severity,'INFO'),COALESCE(l.reason_code,'SUPPORTED'),l.explanations,l.advisory_id::text,l.created_at
        FROM signed s LEFT JOIN latest l ON l.release_id=s.release_id
        ORDER BY s.plugin_id,s.version LIMIT 10000
        """,
            (rs, row) ->
                new Entry(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4).trim(),
                    readStrings(rs.getString(5)),
                    rs.getString(6),
                    rs.getString(7),
                    readStrings(rs.getString(8)),
                    rs.getString(9),
                    rs.getString(10),
                    rs.getString(11),
                    rs.getString(12),
                    rs.getObject(13, OffsetDateTime.class)));
    if (entries.size() >= 10000)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Marketplace advisory snapshot exceeds its item limit");
    ObjectNode payload = mapper.createObjectNode();
    payload.put("schemaVersion", 1);
    payload.put("sequence", state.sequence());
    payload.put("issuedAt", state.issuedAt().toInstant().toString());
    payload.put("expiresAt", state.issuedAt().plus(ttl).toInstant().toString());
    payload.put("keyId", keyId);
    ArrayNode advisoryArray = payload.putArray("advisories");
    for (Entry entry : entries) appendEntry(advisoryArray, entry, state.issuedAt());
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(
                  new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64))));
      signer.update(ReleaseDescriptorCanonicalizer.canonicalBytes(payload.toString()));
      String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
      return Map.of(
          "payload",
          mapper.convertValue(payload, new TypeReference<Map<String, Object>>() {}),
          "signature",
          Map.of("algorithm", "Ed25519", "keyId", keyId, "value", signature));
    } catch (Exception failure) {
      throw new IllegalStateException("Marketplace advisory snapshot could not be signed", failure);
    }
  }

  @Scheduled(cron = "${marketplace.advisory.feed-refresh-cron:0 0 */6 * * *}")
  public void refreshSignedSnapshotLifetime() {
    jdbc.update(
        "UPDATE marketplace_advisory_feed_state SET sequence=sequence+1,issued_at=now() WHERE singleton_id=true");
  }

  private void appendEntry(ArrayNode target, Entry entry, OffsetDateTime feedIssuedAt) {
    ObjectNode item = target.addObject();
    item.put(
        "advisoryId",
        entry.advisoryId() == null
            ? java.util
                .UUID
                .nameUUIDFromBytes(
                    (entry.pluginId() + "@" + entry.version() + ":" + entry.descriptorDigest())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString()
            : entry.advisoryId());
    item.put("pluginId", entry.pluginId());
    item.put("version", entry.version());
    item.put("releaseDescriptorDigest", "sha256:" + entry.descriptorDigest());
    writeStrings(item.putArray("imageDigests"), entry.imageDigests());
    writeStrings(item.putArray("bannedImageDigests"), entry.bannedImageDigests());
    item.put("supportStatus", entry.supportStatus());
    item.put("securityStatus", entry.securityStatus());
    item.put("status", "BANNED".equals(entry.securityStatus()) ? "BANNED" : entry.supportStatus());
    item.put("scope", "RELEASE");
    item.put("severity", entry.severity());
    item.put("reasonCode", entry.reasonCode());
    if (entry.explanationsJson() == null) {
      item.set(
          "explanations",
          mapper.valueToTree(Map.of("en", "Official release is currently supported.")));
    } else {
      item.set("explanations", mapper.valueToTree(readTextMap(entry.explanationsJson())));
    }
    item.put(
        "issuedAt",
        (entry.createdAt() == null ? feedIssuedAt : entry.createdAt()).toInstant().toString());
  }

  private List<String> readStrings(String json) {
    if (json == null) return List.of();
    try {
      return mapper.readValue(json, STRINGS);
    } catch (Exception failure) {
      throw new IllegalStateException("Stored advisory digest list is invalid", failure);
    }
  }

  private Map<String, String> readTextMap(String json) {
    try {
      return mapper.readValue(json, TEXT_MAP);
    } catch (Exception failure) {
      throw new IllegalStateException("Stored advisory explanation is invalid", failure);
    }
  }

  private static void writeStrings(ArrayNode target, List<String> values) {
    values.forEach(target::add);
  }

  private record FeedState(long sequence, OffsetDateTime issuedAt) {}

  private record Entry(
      String releaseId,
      String pluginId,
      String version,
      String descriptorDigest,
      List<String> imageDigests,
      String supportStatus,
      String securityStatus,
      List<String> bannedImageDigests,
      String severity,
      String reasonCode,
      String explanationsJson,
      String advisoryId,
      OffsetDateTime createdAt) {}
}
