package io.artio.marketplace.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Explicit admin and incident-response controls for Core-effective advisory state. */
@RestController
@RequestMapping("/api/v1/admin/marketplace")
public final class SecurityAdvisoryController {
  private final SecurityAdvisoryOperations advisories;
  private final JdbcTemplate jdbc;
  private final String adminTokenHash;
  private final String revocationTokenHash;

  public SecurityAdvisoryController(
      SecurityAdvisoryOperations advisories,
      JdbcTemplate jdbc,
      @Value("${marketplace.admin.token-sha256:}") String adminTokenHash,
      @Value("${marketplace.revocation.token-sha256:}") String revocationTokenHash) {
    this.advisories = advisories;
    this.jdbc = jdbc;
    this.adminTokenHash = adminTokenHash;
    this.revocationTokenHash = revocationTokenHash;
  }

  @PostMapping("/releases/{releaseId}/support")
  public Map<String, Object> support(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody SupportRequest request) {
    authenticate(authorization, adminTokenHash, "admin");
    if (request == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Support status is required");
    int count =
        advisories.setSupport(
            releaseId,
            request.supportStatus(),
            request.reasonCode(),
            request.explanations(),
            "admin");
    audit("SET_SUPPORT_STATUS", releaseId, request.reasonCode());
    return Map.of(
        "releaseId",
        releaseId,
        "supportStatus",
        request.supportStatus(),
        "feedSequence",
        currentSequence(),
        "events",
        count);
  }

  @PostMapping("/releases/{releaseId}/ban")
  public Map<String, Object> banRelease(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody BanRequest request) {
    authenticate(authorization, revocationTokenHash, "revocation");
    if (request == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ban advisory is required");
    int count =
        advisories.banRelease(
            releaseId,
            request.reasonCode(),
            request.severity(),
            request.explanations(),
            "revocation");
    audit("BAN_RELEASE", releaseId, request.reasonCode());
    return Map.of(
        "releaseId",
        releaseId,
        "securityStatus",
        "BANNED",
        "feedSequence",
        currentSequence(),
        "events",
        count);
  }

  @PostMapping("/releases/{releaseId}/ban-image")
  public Map<String, Object> banImage(
      @PathVariable String releaseId,
      @RequestParam String digest,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody BanRequest request) {
    authenticate(authorization, revocationTokenHash, "revocation");
    if (request == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ban advisory is required");
    int count =
        advisories.banImage(
            releaseId,
            digest,
            request.reasonCode(),
            request.severity(),
            request.explanations(),
            "revocation");
    audit("BAN_IMAGE_DIGEST", releaseId, request.reasonCode());
    return Map.of(
        "releaseId",
        releaseId,
        "digest",
        digest,
        "securityStatus",
        "BANNED",
        "feedSequence",
        currentSequence(),
        "events",
        count);
  }

  @PostMapping("/plugins/{pluginId}/ban")
  public Map<String, Object> banPlugin(
      @PathVariable String pluginId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody BanRequest request) {
    authenticate(authorization, revocationTokenHash, "revocation");
    if (request == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ban advisory is required");
    int count =
        advisories.banPlugin(
            pluginId,
            request.reasonCode(),
            request.severity(),
            request.explanations(),
            "revocation");
    audit("BAN_PLUGIN_RELEASES", pluginId, request.reasonCode());
    return Map.of(
        "pluginId",
        pluginId,
        "securityStatus",
        "BANNED",
        "feedSequence",
        currentSequence(),
        "events",
        count);
  }

  private long currentSequence() {
    return jdbc.queryForObject(
        "SELECT sequence FROM marketplace_advisory_feed_state WHERE singleton_id=true", Long.class);
  }

  private void audit(String action, String target, String reasonCode) {
    jdbc.update(
        "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('advisory-operator',?,?, 'OK',?::jsonb)",
        action,
        target,
        "{\"reasonCode\":\"" + reasonCode + "\"}");
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

  public record SupportRequest(
      String supportStatus, String reasonCode, Map<String, String> explanations) {}

  public record BanRequest(String reasonCode, String severity, Map<String, String> explanations) {}
}
