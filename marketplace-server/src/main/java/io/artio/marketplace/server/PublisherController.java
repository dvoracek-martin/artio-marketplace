package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.marketplace.application.ArtifactPromoter;
import io.artio.marketplace.application.ArtifactVerifier;
import io.artio.marketplace.domain.ModerationState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/marketplace")
public class PublisherController {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ManifestRules rules;
  private final ArtifactVerifier artifacts;
  private final ArtifactPromoter promoter;
  private final io.artio.marketplace.infrastructure.MediaStore media;
  private final org.springframework.transaction.support.TransactionTemplate transactions;
  private final SecurityAdvisoryOperations advisories;
  private final String publisherTokenHash;
  private final String adminTokenHash;
  private final String revocationTokenHash;

  public PublisherController(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      ManifestRules rules,
      ArtifactVerifier artifacts,
      ArtifactPromoter promoter,
      io.artio.marketplace.infrastructure.MediaStore media,
      org.springframework.transaction.PlatformTransactionManager transactionManager,
      SecurityAdvisoryOperations advisories,
      @Value("${marketplace.publisher.token-sha256:}") String publisherTokenHash,
      @Value("${marketplace.admin.token-sha256:}") String adminTokenHash,
      @Value("${marketplace.revocation.token-sha256:}") String revocationTokenHash) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.rules = rules;
    this.artifacts = artifacts;
    this.promoter = promoter;
    this.media = media;
    this.transactions =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    this.advisories = advisories;
    this.publisherTokenHash = publisherTokenHash;
    this.adminTokenHash = adminTokenHash;
    this.revocationTokenHash = revocationTokenHash;
    var configuredCredentials =
        List.of(publisherTokenHash, adminTokenHash, revocationTokenHash).stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.toLowerCase(java.util.Locale.ROOT))
            .toList();
    if (configuredCredentials.stream().distinct().count() != configuredCredentials.size())
      throw new IllegalArgumentException(
          "Publisher, admin, and revocation credentials must be unique");
  }

  @PostMapping("/releases/validate")
  public Map<String, Object> validate(
      @RequestBody Submission request,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, publisherTokenHash, "publisher");
    var validation = rules.validate(request.manifest(), request.catalog());
    var errors = new java.util.ArrayList<>(validation.errors());
    if (validation.valid())
      errors.addAll(artifacts.verify(rules.registryHost(), json(request.manifest())));
    return Map.of(
        "valid",
        errors.isEmpty(),
        "errors",
        errors,
        "manifestDigest",
        sha(json(request.manifest())));
  }

  @PostMapping("/releases")
  public ResponseEntity<Map<String, Object>> create(
      @RequestBody Submission request,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    authenticate(auth, publisherTokenHash, "publisher");
    if (request.manifest() == null || request.catalog() == null)
      throw new IllegalArgumentException("manifest and catalog are required");
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,160}"))
      throw new IllegalArgumentException("Idempotency-Key is required (8 to 160 safe characters)");
    var validation = rules.validate(request.manifest(), request.catalog());
    var errors = new java.util.ArrayList<>(validation.errors());
    if (!errors.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
    String id = validation.manifest().id(),
        version = validation.manifest().version(),
        slug = request.catalog().path("slug").asText();
    String manifest = json(request.manifest());
    String metadata = json(request.catalog());
    String requestDigest = sha(manifest + "\\n" + metadata);
    var prior =
        jdbc.query(
            "SELECT release_id::text,plugin_id,version,manifest_sha256,create_request_sha256,publication_state FROM marketplace_releases WHERE idempotency_key=?",
            (rs, n) ->
                Map.of(
                    "id",
                    rs.getString(1),
                    "pluginId",
                    rs.getString(2),
                    "version",
                    rs.getString(3),
                    "digest",
                    rs.getString(4),
                    "requestDigest",
                    rs.getString(5),
                    "state",
                    rs.getString(6)),
            key);
    String digest = sha(manifest);
    if (!prior.isEmpty()) {
      var p = prior.getFirst();
      if (!p.get("pluginId").equals(id)
          || !p.get("version").equals(version)
          || !p.get("digest").equals(digest)
          || !p.get("requestDigest").equals(requestDigest))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Idempotency-Key was already used for different release content");
      return ResponseEntity.ok(
          Map.of("releaseId", p.get("id"), "state", p.get("state"), "idempotent", true));
    }
    errors.addAll(artifacts.verify(rules.registryHost(), manifest));
    if (!errors.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
    String createdReleaseId;
    try {
      createdReleaseId =
          transactions.execute(
              status -> {
                jdbc.update(
                    "INSERT INTO marketplace_plugins(plugin_id,slug,vendor) VALUES (?,?,?) ON CONFLICT(plugin_id) DO NOTHING",
                    id,
                    slug,
                    validation.manifest().vendor());
                var owner =
                    jdbc.query(
                            "SELECT slug,vendor FROM marketplace_plugins WHERE plugin_id=?",
                            (rs, n) -> List.of(rs.getString(1), rs.getString(2)),
                            id)
                        .getFirst();
                if (!owner.get(0).equals(slug))
                  throw new ResponseStatusException(
                      HttpStatus.CONFLICT, "Plugin id is already reserved to another slug");
                jdbc.update(
                    "INSERT INTO marketplace_releases(release_id,plugin_id,version,manifest,manifest_document,manifest_sha256,metadata,publication_state,idempotency_key,create_request_sha256) VALUES (?::uuid,?,?,?::jsonb,?,?,?::jsonb,'VALIDATED',?,?)",
                    UUID.randomUUID().toString(),
                    id,
                    version,
                    manifest,
                    manifest,
                    digest,
                    metadata,
                    key,
                    requestDigest);
                audit("CREATE_VALIDATED", id + "@" + version, "OK");
                return findReleaseId(id, version);
              });
    } catch (org.springframework.dao.DuplicateKeyException conflict) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plugin release version or idempotency key already exists");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "releaseId",
                createdReleaseId,
                "pluginId",
                id,
                "version",
                version,
                "state",
                "VALIDATED"));
  }

  @GetMapping("/releases/{releaseId}")
  public Map<String, Object> draft(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, publisherTokenHash, "publisher");
    return lookupDraft(releaseId);
  }

  @GetMapping("/releases/{releaseId}/review")
  public Map<String, Object> reviewDraft(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, adminTokenHash, "admin");
    return lookupDraft(releaseId);
  }

  @PutMapping("/releases/{releaseId}/catalog")
  @Transactional
  public Map<String, Object> replaceCatalog(
      @PathVariable String releaseId,
      @RequestBody JsonNode catalog,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, publisherTokenHash, "publisher");
    var r = releaseRow(releaseId);
    var manifest = parse(r.get("manifest"));
    var v = rules.validate(manifest, catalog);
    if (!v.errors().isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", v.errors()));
    int updated =
        jdbc.update(
            "UPDATE marketplace_releases SET metadata=?::jsonb,publication_state='VALIDATED',publication_approval_state='PENDING',publication_approved_by=NULL,publication_approved_at=NULL,approved_manifest_sha256=NULL,approved_catalog_sha256=NULL,row_version=row_version+1 WHERE release_id=?::uuid AND publication_state IN ('DRAFT','VALIDATED') AND row_version=?",
            json(catalog),
            releaseId,
            Long.parseLong(r.get("rowVersion")));
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Release is no longer editable");
    audit("UPDATE_CATALOG", r.get("pluginId") + "@" + r.get("version"), "OK");
    return lookupDraft(releaseId);
  }

  @PostMapping("/releases/{releaseId}/approve")
  @Transactional
  public Map<String, Object> approve(
      @PathVariable String releaseId,
      @RequestBody(required = false) ApprovalRequest request,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, adminTokenHash, "admin");
    if (request == null
        || request.reason() == null
        || request.reason().isBlank()
        || request.reason().length() > 1000)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "An approval review reason is required");

    var release = releaseRow(releaseId);
    if (!"VALIDATED".equals(release.get("state")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only a validated draft can be approved");
    if (!"ACTIVE".equals(release.get("moderation")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plugin moderation blocks release approval");

    String manifestDigest = release.get("manifestSha256");
    String catalogDigest = sha(release.get("metadata"));
    if (!manifestDigest.equals(sha(release.get("manifest"))))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Stored manifest digest is inconsistent");

    if ("APPROVED".equals(release.get("approvalState"))) {
      if (manifestDigest.equals(release.get("approvedManifestSha256"))
          && catalogDigest.equals(release.get("approvedCatalogSha256")))
        return Map.of(
            "releaseId",
            releaseId,
            "approvalState",
            "APPROVED",
            "approvedBy",
            release.get("approvedBy"),
            "idempotent",
            true);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Approval no longer matches the staged release content");
    }

    int changed =
        jdbc.update(
            "UPDATE marketplace_releases SET publication_approval_state='APPROVED',publication_approved_by='admin',publication_approved_at=now(),approved_manifest_sha256=?,approved_catalog_sha256=?,row_version=row_version+1 WHERE release_id=?::uuid AND publication_state='VALIDATED' AND publication_approval_state='PENDING' AND row_version=? AND manifest_sha256=?",
            manifestDigest,
            catalogDigest,
            releaseId,
            Long.parseLong(release.get("rowVersion")),
            manifestDigest);
    if (changed != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Release changed during approval; review the current draft and retry");
    jdbc.update(
        "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('admin','APPROVE_RELEASE',?,'OK',?::jsonb)",
        release.get("pluginId") + "@" + release.get("version"),
        json(
            mapper.valueToTree(
                Map.of(
                    "reason", request.reason(),
                    "manifestSha256", manifestDigest,
                    "catalogSha256", catalogDigest))));
    return Map.of(
        "releaseId", releaseId,
        "approvalState", "APPROVED",
        "approvedBy", "admin",
        "manifestSha256", manifestDigest,
        "catalogSha256", catalogDigest);
  }

  @org.springframework.web.bind.annotation.PostMapping(
      value = "/releases/{releaseId}/assets",
      consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> uploadAsset(
      @PathVariable String releaseId,
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
      @RequestParam("kind") String kind,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, publisherTokenHash, "publisher");
    var r = releaseRow(releaseId);
    if (!List.of("DRAFT", "VALIDATED").contains(r.get("state")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Assets can only be uploaded to a draft release");
    var asset = media.store(file, kind);
    audit("UPLOAD_ASSET", r.get("pluginId") + "@" + r.get("version") + "/" + asset.assetId(), "OK");
    return Map.of(
        "assetId",
        asset.assetId(),
        "contentType",
        asset.contentType(),
        "size",
        asset.size(),
        "width",
        asset.width(),
        "height",
        asset.height());
  }

  @PostMapping("/releases/{releaseId}/publish")
  public Map<String, Object> publish(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, publisherTokenHash, "publisher");
    var r = releaseRow(releaseId);
    if ("PUBLISHED".equals(r.get("state")))
      return Map.of(
          "releaseId",
          releaseId,
          "pluginId",
          r.get("pluginId"),
          "version",
          r.get("version"),
          "state",
          "PUBLISHED",
          "idempotent",
          true);
    if (!"VALIDATED".equals(r.get("state")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Release is not in a publishable state");
    if (!"APPROVED".equals(r.get("approvalState"))
        || !r.get("manifestSha256").equals(r.get("approvedManifestSha256"))
        || !sha(r.get("metadata")).equals(r.get("approvedCatalogSha256")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Release requires separate admin approval for the exact manifest and catalog content");
    if (!"ACTIVE".equals(r.get("moderation")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plugin moderation blocks publication");
    Integer reserved =
        transactions.execute(
            status -> {
              int changed =
                  jdbc.update(
                      "UPDATE marketplace_releases SET publication_state='PUBLISHING',publishing_started_at=now(),row_version=row_version+1 WHERE release_id=?::uuid AND publication_state='VALIDATED' AND publication_approval_state='APPROVED' AND approved_manifest_sha256=manifest_sha256 AND approved_catalog_sha256=? AND EXISTS (SELECT 1 FROM marketplace_plugins p WHERE p.plugin_id=marketplace_releases.plugin_id AND p.moderation_state='ACTIVE')",
                      releaseId,
                      sha(r.get("metadata")));
              if (changed == 1)
                jdbc.update(
                    "INSERT INTO publication_promotions(release_id,state,attempt_count,started_at,updated_at) VALUES (?::uuid,'PROMOTING',1,now(),now()) ON CONFLICT(release_id) DO UPDATE SET state='PROMOTING',attempt_count=publication_promotions.attempt_count+1,started_at=now(),completed_at=NULL,last_error_code=NULL,updated_at=now()",
                    releaseId);
              return changed;
            });
    if (reserved != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Release or plugin moderation changed; retry");
    var reservedRow = releaseRow(releaseId);
    long reservationVersion = Long.parseLong(reservedRow.get("rowVersion"));
    var validation = rules.validate(parse(r.get("manifest")), parse(r.get("metadata")));
    if (!validation.valid()) {
      releaseReservation(releaseId, reservationVersion);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, String.join("; ", validation.errors()));
    }
    List<String> artifactErrors;
    try {
      artifactErrors = artifacts.verify(rules.registryHost(), reservedRow.get("manifest"));
    } catch (RuntimeException failure) {
      releaseReservation(releaseId, reservationVersion);
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Registry verification failed; retry", failure);
    }
    if (!artifactErrors.isEmpty()) {
      releaseReservation(releaseId, reservationVersion);
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, String.join("; ", artifactErrors));
    }
    try {
      promoter.promote(reservedRow.get("manifest"));
      artifactErrors = artifacts.verifyPublished(rules.registryHost(), reservedRow.get("manifest"));
    } catch (RuntimeException failure) {
      releaseReservation(releaseId, reservationVersion);
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Approved OCI promotion failed; retry is safe", failure);
    }
    if (!artifactErrors.isEmpty()) {
      releaseReservation(releaseId, reservationVersion);
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Public OCI verification failed: " + String.join("; ", artifactErrors));
    }
    JsonNode metadata = parse(r.get("metadata"));
    if (!assetExists(metadata.path("icon").asText())) {
      releaseReservation(releaseId, reservationVersion);
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Catalog icon asset is missing");
    }
    JsonNode shots = metadata.path("screenshots");
    if (shots.isArray())
      for (JsonNode shot : shots)
        if (!assetExists(shot.path("assetId").asText())) {
          releaseReservation(releaseId, reservationVersion);
          throw new ResponseStatusException(HttpStatus.CONFLICT, "A screenshot asset is missing");
        }
    int updated;
    try {
      updated =
          transactions.execute(
              status -> {
                int changed =
                    jdbc.update(
                        "UPDATE marketplace_releases SET publication_state='PUBLISHED',published_at=now(),publishing_started_at=NULL,row_version=row_version+1 WHERE release_id=?::uuid AND publication_state='PUBLISHING' AND row_version=? AND publication_approval_state='APPROVED' AND approved_manifest_sha256=manifest_sha256 AND approved_catalog_sha256=? AND EXISTS (SELECT 1 FROM marketplace_plugins p WHERE p.plugin_id=marketplace_releases.plugin_id AND p.moderation_state='ACTIVE')",
                        releaseId,
                        reservationVersion,
                        sha(r.get("metadata")));
                if (changed == 1) {
                  int promotionUpdated =
                      jdbc.update(
                          "UPDATE publication_promotions SET state='PUBLISHED',completed_at=now(),last_error_code=NULL,updated_at=now() WHERE release_id=?::uuid AND state='PROMOTING'",
                          releaseId);
                  if (promotionUpdated != 1)
                    throw new IllegalStateException("Durable OCI promotion job is missing");
                  audit("PUBLISH", r.get("pluginId") + "@" + r.get("version"), "OK");
                }
                return changed;
              });
    } catch (RuntimeException failure) {
      releaseReservation(releaseId, reservationVersion);
      throw failure;
    }
    if (updated != 1) {
      releaseReservation(releaseId, reservationVersion);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Release changed while publishing; retry");
    }
    return Map.of(
        "releaseId",
        releaseId,
        "pluginId",
        r.get("pluginId"),
        "version",
        r.get("version"),
        "state",
        "PUBLISHED",
        "publishedAt",
        OffsetDateTime.now());
  }

  private void releaseReservation(String releaseId, long reservationVersion) {
    jdbc.update(
        "UPDATE marketplace_releases SET publication_state='VALIDATED',publishing_started_at=NULL,row_version=row_version+1 WHERE release_id=?::uuid AND publication_state='PUBLISHING' AND row_version=?",
        releaseId,
        reservationVersion);
    jdbc.update(
        "UPDATE publication_promotions SET state='RETRYABLE',completed_at=NULL,last_error_code='PROMOTION_INCOMPLETE',updated_at=now() WHERE release_id=?::uuid AND state='PROMOTING'",
        releaseId);
  }

  @PostMapping("/releases/{releaseId}/unlist")
  @Transactional
  public Map<String, Object> unlist(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, adminTokenHash, "admin");
    return statusChange(releaseId, "PUBLISHED", "UNLISTED", "UNLIST", "admin", null);
  }

  @PostMapping("/releases/{releaseId}/revoke")
  @Transactional
  public Map<String, Object> revoke(
      @PathVariable String releaseId,
      @RequestBody(required = false) RevocationRequest request,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, revocationTokenHash, "revocation");
    if (request == null
        || request.reason() == null
        || request.reason().isBlank()
        || request.reason().length() > 1000
        || request.category() == null
        || !List.of("SECURITY", "MALWARE", "POLICY", "LEGAL", "OTHER").contains(request.category()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Revocation category and reason are required");
    return statusChange(releaseId, "PUBLISHED", "REVOKED", "REVOKE", "revocation", request);
  }

  @GetMapping("/publication-audit")
  public List<Map<String, Object>> audit(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    authenticate(auth, adminTokenHash, "admin");
    if (page < 0 || size < 1 || size > 100)
      throw new IllegalArgumentException("invalid pagination");
    return jdbc.query(
        "SELECT actor,action,target,outcome,correlation_id,created_at FROM marketplace_audit ORDER BY audit_id DESC LIMIT ? OFFSET ?",
        (rs, n) ->
            Map.of(
                "actor",
                rs.getString(1),
                "action",
                rs.getString(2),
                "target",
                rs.getString(3),
                "outcome",
                rs.getString(4),
                "correlationId",
                rs.getString(5) == null ? "" : rs.getString(5),
                "createdAt",
                rs.getObject(6, OffsetDateTime.class)),
        size,
        page * size);
  }

  private Map<String, Object> statusChange(
      String releaseId,
      String from,
      String to,
      String action,
      String actor,
      RevocationRequest request) {
    var r = releaseRow(releaseId);
    int n;
    if (request != null) {
      n =
          jdbc.update(
              "UPDATE marketplace_releases SET publication_state=?,revoked_at=now(),revocation_category=?,revocation_reason=?,revoked_by=?,row_version=row_version+1 WHERE release_id=?::uuid AND publication_state=?",
              to,
              request.category(),
              request.reason(),
              actor,
              releaseId,
              from);
    } else {
      n =
          jdbc.update(
              "UPDATE marketplace_releases SET publication_state=?,row_version=row_version+1 WHERE release_id=?::uuid AND publication_state=?",
              to,
              releaseId,
              from);
    }
    if (n != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Release cannot transition from its current state");
    jdbc.update(
        "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES (?,?,?,'OK',?::jsonb)",
        actor,
        action,
        r.get("pluginId") + "@" + r.get("version"),
        request == null
            ? "{}"
            : json(
                mapper.valueToTree(
                    Map.of("category", request.category(), "reason", request.reason()))));
    if ("UNLISTED".equals(to)) {
      advisories.recordPublicationRemoval(
          releaseId,
          "REMOVED",
          null,
          "MARKETPLACE_UNLISTED",
          "INFO",
          Map.of(
              "en",
              "This release was removed from the official Marketplace.",
              "de",
              "Diese Version wurde aus dem offiziellen Marketplace entfernt.",
              "fr",
              "Cette version a été retirée de la Marketplace officielle.",
              "it",
              "Questa versione è stata rimossa dal Marketplace ufficiale."),
          actor);
    } else if (request != null) {
      boolean securityBan = List.of("SECURITY", "MALWARE").contains(request.category());
      advisories.recordPublicationRemoval(
          releaseId,
          "REMOVED",
          securityBan ? "BANNED" : null,
          "RELEASE_REVOKED_" + request.category(),
          securityBan ? "CRITICAL" : "INFO",
          Map.of(
              "en",
              request.reason(),
              "de",
              "Diese Version wurde wegen " + request.category() + " gesperrt.",
              "fr",
              "Cette version a été bloquée pour " + request.category() + ".",
              "it",
              "Questa versione è stata bloccata per " + request.category() + "."),
          actor);
    }
    return Map.of("releaseId", releaseId, "state", to);
  }

  @org.springframework.transaction.annotation.Transactional
  @PostMapping("/plugins/{pluginId}/moderation")
  public Map<String, Object> moderatePlugin(
      @PathVariable String pluginId,
      @RequestBody ModerationRequest request,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth, adminTokenHash, "admin");
    if (request == null
        || !List.of("ACTIVE", "SUSPENDED", "BANNED").contains(request.state())
        || request.reason() == null
        || request.reason().isBlank()
        || request.reason().length() > 1000)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A moderation state and reason are required");
    var currentRows =
        jdbc.query(
            "SELECT moderation_state,moderation_version FROM marketplace_plugins WHERE plugin_id=?",
            (rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)),
            pluginId);
    if (currentRows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found");
    var current = ModerationState.valueOf(currentRows.getFirst().getKey());
    var next = ModerationState.valueOf(request.state());
    try {
      current.transitionTo(next);
    } catch (IllegalStateException invalid) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, invalid.getMessage());
    }
    int changed =
        jdbc.update(
            "UPDATE marketplace_plugins SET moderation_state=?,moderation_review_reason=?,moderation_updated_at=now(),moderation_version=moderation_version+1,updated_at=now() WHERE plugin_id=? AND moderation_state=? AND moderation_version=?",
            next.name(),
            request.reason(),
            pluginId,
            current.name(),
            currentRows.getFirst().getValue());
    if (changed != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plugin moderation changed concurrently; retry");
    jdbc.update(
        "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('admin','PLUGIN_MODERATION',?,'OK',?::jsonb)",
        pluginId,
        json(mapper.valueToTree(Map.of("state", request.state(), "reason", request.reason()))));
    return Map.of("pluginId", pluginId, "moderationState", request.state());
  }

  private record Submission(JsonNode manifest, JsonNode catalog) {}

  private record ApprovalRequest(String reason) {}

  private record ModerationRequest(String state, String reason) {}

  private record RevocationRequest(String category, String reason) {}

  private void authenticate(String authorization, String tokenHash, String actor) {
    if (tokenHash == null || tokenHash.isBlank())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, actor + " authentication is not configured");
    if (authorization == null || !authorization.startsWith("Bearer "))
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Publisher bearer token is required");
    String provided = sha(authorization.substring(7));
    if (!MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.US_ASCII),
        tokenHash.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, actor + " token is invalid");
  }

  private String findReleaseId(String id, String version) {
    return jdbc.queryForObject(
        "SELECT release_id::text FROM marketplace_releases WHERE plugin_id=? AND version=?",
        String.class,
        id,
        version);
  }

  private Map<String, String> releaseRow(String id) {
    try {
      return jdbc.queryForObject(
          "SELECT r.plugin_id,r.version,r.manifest_document,r.metadata::text,r.publication_state,r.row_version,p.moderation_state,r.manifest_sha256,r.publication_approval_state,coalesce(r.publication_approved_by,''),coalesce(r.approved_manifest_sha256,''),coalesce(r.approved_catalog_sha256,'') FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id WHERE r.release_id=?::uuid",
          (rs, n) ->
              Map.ofEntries(
                  Map.entry("pluginId", rs.getString(1)),
                  Map.entry("version", rs.getString(2)),
                  Map.entry("manifest", rs.getString(3)),
                  Map.entry("metadata", rs.getString(4)),
                  Map.entry("state", rs.getString(5)),
                  Map.entry("rowVersion", rs.getString(6)),
                  Map.entry("moderation", rs.getString(7)),
                  Map.entry("manifestSha256", rs.getString(8)),
                  Map.entry("approvalState", rs.getString(9)),
                  Map.entry("approvedBy", rs.getString(10)),
                  Map.entry("approvedManifestSha256", rs.getString(11)),
                  Map.entry("approvedCatalogSha256", rs.getString(12))),
          id);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found");
    }
  }

  private Map<String, Object> lookupDraft(String id) {
    var r = releaseRow(id);
    return Map.ofEntries(
        Map.entry("releaseId", id),
        Map.entry("pluginId", r.get("pluginId")),
        Map.entry("version", r.get("version")),
        Map.entry("state", r.get("state")),
        Map.entry("approvalState", r.get("approvalState")),
        Map.entry("approvedBy", r.get("approvedBy")),
        Map.entry("manifestSha256", r.get("manifestSha256")),
        Map.entry("approvedManifestSha256", r.get("approvedManifestSha256")),
        Map.entry("approvedCatalogSha256", r.get("approvedCatalogSha256")),
        Map.entry("manifest", parse(r.get("manifest"))),
        Map.entry("catalog", parse(r.get("metadata"))));
  }

  private boolean assetExists(String id) {
    return jdbc.queryForObject(
        "SELECT count(*)>0 FROM marketplace_media_assets WHERE asset_id=?", Boolean.class, id);
  }

  private void audit(String action, String target, String outcome) {
    jdbc.update(
        "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('publisher',?,?,?,'{}'::jsonb)",
        action,
        target,
        outcome);
  }

  private String json(JsonNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON request");
    }
  }

  private JsonNode parse(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("Stored JSON is corrupt");
    }
  }

  private static String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
