package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.marketplace.application.ArtifactVerifier;
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
  private final io.artio.marketplace.infrastructure.MediaStore media;
  private final String tokenHash;

  public PublisherController(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      ManifestRules rules,
      ArtifactVerifier artifacts,
      io.artio.marketplace.infrastructure.MediaStore media,
      @Value("${marketplace.publisher.token-sha256:}") String tokenHash) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.rules = rules;
    this.artifacts = artifacts;
    this.media = media;
    this.tokenHash = tokenHash;
  }

  @PostMapping("/releases/validate")
  public Map<String, Object> validate(
      @RequestBody Submission request,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth);
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
  @Transactional
  public ResponseEntity<Map<String, Object>> create(
      @RequestBody Submission request,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    authenticate(auth);
    if (request.manifest() == null || request.catalog() == null)
      throw new IllegalArgumentException("manifest and catalog are required");
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,160}"))
      throw new IllegalArgumentException("Idempotency-Key is required (8 to 160 safe characters)");
    var validation = rules.validate(request.manifest(), request.catalog());
    var errors = new java.util.ArrayList<>(validation.errors());
    if (validation.valid())
      errors.addAll(artifacts.verify(rules.registryHost(), json(request.manifest())));
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
    try {
      jdbc.update(
          "INSERT INTO marketplace_plugins(plugin_id,slug,vendor,listing_state) VALUES (?,?,?,'UNLISTED') ON CONFLICT(plugin_id) DO NOTHING",
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
    } catch (org.springframework.dao.DuplicateKeyException conflict) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plugin release version or idempotency key already exists");
    }
    audit("CREATE_VALIDATED", id + "@" + version, "OK");
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "releaseId",
                findReleaseId(id, version),
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
    authenticate(auth);
    return lookupDraft(releaseId);
  }

  @PutMapping("/releases/{releaseId}/catalog")
  @Transactional
  public Map<String, Object> replaceCatalog(
      @PathVariable String releaseId,
      @RequestBody JsonNode catalog,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth);
    var r = releaseRow(releaseId);
    var manifest = parse(r.get("manifest"));
    var v = rules.validate(manifest, catalog);
    if (!v.errors().isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", v.errors()));
    jdbc.update(
        "UPDATE marketplace_releases SET metadata=?::jsonb,publication_state='VALIDATED' WHERE release_id=?::uuid AND publication_state IN ('DRAFT','VALIDATED')",
        json(catalog),
        releaseId);
    audit("UPDATE_CATALOG", r.get("pluginId") + "@" + r.get("version"), "OK");
    return lookupDraft(releaseId);
  }

  @org.springframework.web.bind.annotation.PostMapping(
      value = "/releases/{releaseId}/assets",
      consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> uploadAsset(
      @PathVariable String releaseId,
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
      @RequestParam("kind") String kind,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth);
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
  @Transactional
  public Map<String, Object> publish(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    authenticate(auth);
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
    var validation = rules.validate(parse(r.get("manifest")), parse(r.get("metadata")));
    if (!validation.valid())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, String.join("; ", validation.errors()));
    var artifactErrors = artifacts.verify(rules.registryHost(), r.get("manifest"));
    if (!artifactErrors.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, String.join("; ", artifactErrors));
    JsonNode metadata = parse(r.get("metadata"));
    if (!assetExists(metadata.path("icon").asText()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Catalog icon asset is missing");
    JsonNode shots = metadata.path("screenshots");
    if (shots.isArray())
      for (JsonNode shot : shots)
        if (!assetExists(shot.path("assetId").asText()))
          throw new ResponseStatusException(HttpStatus.CONFLICT, "A screenshot asset is missing");
    int updated =
        jdbc.update(
            "UPDATE marketplace_releases SET publication_state='PUBLISHED',published_at=now() WHERE release_id=?::uuid AND publication_state='VALIDATED'",
            releaseId);
    if (updated != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Release changed while publishing; retry");
    jdbc.update(
        "UPDATE marketplace_plugins SET listing_state='LISTED',updated_at=now() WHERE plugin_id=?",
        r.get("pluginId"));
    audit("PUBLISH", r.get("pluginId") + "@" + r.get("version"), "OK");
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

  @PostMapping("/releases/{releaseId}/unlist")
  public Map<String, Object> unlist(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return statusChange(releaseId, auth, "PUBLISHED", "UNLISTED", "UNLIST");
  }

  @PostMapping("/releases/{releaseId}/revoke")
  public Map<String, Object> revoke(
      @PathVariable String releaseId,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return statusChange(releaseId, auth, "PUBLISHED", "REVOKED", "REVOKE");
  }

  @GetMapping("/publication-audit")
  public List<Map<String, Object>> audit(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    authenticate(auth);
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
      String releaseId, String auth, String from, String to, String action) {
    authenticate(auth);
    var r = releaseRow(releaseId);
    int n =
        jdbc.update(
            "UPDATE marketplace_releases SET publication_state=? WHERE release_id=?::uuid AND publication_state=?",
            to,
            releaseId,
            from);
    if (n != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Release cannot transition from its current state");
    if (to.equals("REVOKED"))
      jdbc.update(
          "UPDATE marketplace_plugins SET listing_state='REVOKED',updated_at=now() WHERE plugin_id=?",
          r.get("pluginId"));
    else
      jdbc.update(
          "UPDATE marketplace_plugins SET listing_state='UNLISTED',updated_at=now() WHERE plugin_id=?",
          r.get("pluginId"));
    audit(action, r.get("pluginId") + "@" + r.get("version"), "OK");
    return Map.of("releaseId", releaseId, "state", to);
  }

  private record Submission(JsonNode manifest, JsonNode catalog) {}

  private void authenticate(String authorization) {
    if (tokenHash.isBlank())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Publisher authentication is not configured");
    if (authorization == null || !authorization.startsWith("Bearer "))
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Publisher bearer token is required");
    String provided = sha(authorization.substring(7));
    if (!MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.US_ASCII),
        tokenHash.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Publisher token is invalid");
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
          "SELECT plugin_id,version,manifest_document,metadata::text,publication_state FROM marketplace_releases WHERE release_id=?::uuid",
          (rs, n) ->
              Map.of(
                  "pluginId",
                  rs.getString(1),
                  "version",
                  rs.getString(2),
                  "manifest",
                  rs.getString(3),
                  "metadata",
                  rs.getString(4),
                  "state",
                  rs.getString(5)),
          id);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found");
    }
  }

  private Map<String, Object> lookupDraft(String id) {
    var r = releaseRow(id);
    return Map.of(
        "releaseId",
        id,
        "pluginId",
        r.get("pluginId"),
        "version",
        r.get("version"),
        "state",
        r.get("state"),
        "manifest",
        parse(r.get("manifest")),
        "catalog",
        parse(r.get("metadata")));
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
