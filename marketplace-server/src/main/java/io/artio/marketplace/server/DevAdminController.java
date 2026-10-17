package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * Local-only browser adapter. Release mutations delegate to the established publisher controller.
 */
@RestController
@Profile("marketplace-local-dev")
@RequestMapping("/api/v1/dev-admin")
public class DevAdminController {
  private final JdbcTemplate jdbc;
  private final PublisherController publisher;
  private final LocalDevCredential credential;
  private final LocalDevReleaseSigner signer;
  private final ManifestRules rules;
  private final ObjectMapper mapper;
  private final String zotUrl;
  private final String registryHost;

  public DevAdminController(
      JdbcTemplate jdbc,
      PublisherController publisher,
      LocalDevCredential credential,
      LocalDevReleaseSigner signer,
      ManifestRules rules,
      ObjectMapper mapper,
      @org.springframework.beans.factory.annotation.Value(
              "${MARKETPLACE_ZOT_URL:http://127.0.0.1:5000}")
          String zotUrl,
      @org.springframework.beans.factory.annotation.Value(
              "${MARKETPLACE_REGISTRY_HOST:localhost:5000}")
          String registryHost) {
    this.jdbc = jdbc;
    this.publisher = publisher;
    this.credential = credential;
    this.signer = signer;
    this.rules = rules;
    this.mapper = mapper;
    this.zotUrl = zotUrl;
    this.registryHost = registryHost;
  }

  @GetMapping("/session")
  public Map<String, Object> session(HttpServletRequest request) {
    String token = DevAdminSecurityFilter.createCsrfToken();
    request.getSession(true).setAttribute(DevAdminSecurityFilter.CSRF_ATTRIBUTE, token);
    return Map.of("csrfToken", token, "apiVersion", 1);
  }

  @GetMapping("/operations")
  public Map<String, Object> operations(HttpServletRequest request) {
    String database = "UP";
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
    } catch (Exception e) {
      database = "DOWN";
    }
    String zot = "DOWN";
    java.net.HttpURLConnection connection = null;
    try {
      connection =
          (java.net.HttpURLConnection)
              java.net.URI.create(zotUrl + "/v2/").toURL().openConnection();
      connection.setConnectTimeout(1500);
      connection.setReadTimeout(1500);
      connection.setRequestMethod("GET");
      if (connection.getResponseCode() == 200) zot = "UP";
    } catch (Exception ignored) {
      // The status endpoint reports a safe health label only; it never returns exception details.
    } finally {
      if (connection != null) connection.disconnect();
    }
    String host = request.getHeader("Host");
    return Map.of(
        "environment",
        "marketplace-local-dev",
        "apiBase",
        "http://" + host,
        "publicRegistry",
        "http://" + registryHost,
        "api",
        "UP",
        "database",
        database,
        "zot",
        zot);
  }

  @GetMapping("/plugins")
  public List<Map<String, Object>> plugins() {
    return jdbc.query(
        """
        SELECT p.plugin_id,p.slug,p.vendor,p.moderation_state,p.moderation_review_reason,
          p.listing_revision_version,p.effective_listing_revision_id::text,
          (SELECT count(*) FROM marketplace_releases r WHERE r.plugin_id=p.plugin_id) AS release_count,
          (SELECT r.version FROM marketplace_releases r WHERE r.plugin_id=p.plugin_id AND r.publication_state='PUBLISHED' ORDER BY marketplace_semver_sort_key(r.version) DESC,r.version COLLATE "C" DESC LIMIT 1) AS newest_published,
          coalesce(lr.metadata->'categories',latest.metadata->'categories','[]'::jsonb)::text AS categories
        FROM marketplace_plugins p
        LEFT JOIN marketplace_listing_revisions lr ON lr.revision_id=p.effective_listing_revision_id AND lr.approval_state='APPROVED'
        LEFT JOIN LATERAL (SELECT r.metadata FROM marketplace_releases r WHERE r.plugin_id=p.plugin_id AND r.publication_state IN ('PUBLISHED','UNLISTED') ORDER BY marketplace_semver_sort_key(r.version) DESC,r.version COLLATE "C" DESC LIMIT 1) latest ON true
        ORDER BY p.plugin_id
        """,
        (rs, n) ->
            Map.<String, Object>ofEntries(
                Map.entry("pluginId", rs.getString(1)),
                Map.entry("slug", rs.getString(2)),
                Map.entry("vendor", rs.getString(3)),
                Map.entry("moderationState", rs.getString(4)),
                Map.entry("moderationReason", rs.getString(5) == null ? "" : rs.getString(5)),
                Map.entry("listingRevisionVersion", rs.getLong(6)),
                Map.entry("effectiveRevisionId", rs.getString(7) == null ? "" : rs.getString(7)),
                Map.entry("releaseCount", rs.getLong(8)),
                Map.entry("newestPublished", rs.getString(9) == null ? "" : rs.getString(9)),
                Map.entry("categories", stringList(rs.getString(10)))));
  }

  @GetMapping("/plugins/page")
  public Map<String, Object> pluginPageEndpoint(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "ALL") String category,
      @RequestParam(defaultValue = "ALL") String moderation,
      @RequestParam(defaultValue = "ALL") String manifestVersion,
      @RequestParam(defaultValue = "") String coreVersion,
      @RequestParam(defaultValue = "ALL") String country,
      @RequestParam(defaultValue = "ALL") String releaseState,
      @RequestParam(defaultValue = "ALL") String approvalState,
      @RequestParam(defaultValue = "ALL") String signed) {
    return pluginPage(
        page,
        size,
        q,
        category,
        moderation,
        manifestVersion,
        coreVersion,
        country,
        releaseState,
        approvalState,
        signed);
  }

  Map<String, Object> pluginPage(int page, int size, String q, String category, String moderation) {
    return pluginPage(page, size, q, category, moderation, "ALL", "", "ALL", "ALL", "ALL", "ALL");
  }

  Map<String, Object> pluginPage(
      int page,
      int size,
      String q,
      String category,
      String moderation,
      String manifestVersion,
      String coreVersion,
      String country,
      String releaseState,
      String approvalState,
      String signed) {
    if (page < 0 || size < 1 || size > 100) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid plugin page or page size.");
    }
    String normalizedCoreVersion = coreVersion == null ? "" : coreVersion.trim();
    if (!normalizedCoreVersion.isEmpty()) {
      try {
        io.artio.marketplace.domain.SemanticVersion.parse(normalizedCoreVersion);
      } catch (IllegalArgumentException invalid) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Core semantic version.");
      }
    }
    String normalizedQuery = q == null ? "" : q.trim();
    String search = "%" + normalizedQuery + "%";
    String normalizedCategory = category == null ? "ALL" : category;
    String normalizedModeration = moderation == null ? "ALL" : moderation;
    String normalizedManifestVersion = manifestVersion == null ? "ALL" : manifestVersion;
    String normalizedCountry =
        country == null ? "ALL" : country.trim().toUpperCase(java.util.Locale.ROOT);
    String normalizedReleaseState = releaseState == null ? "ALL" : releaseState;
    String normalizedApprovalState = approvalState == null ? "ALL" : approvalState;
    String normalizedSigned = signed == null ? "ALL" : signed;
    if (!java.util.Set.of("ALL", "1", "2").contains(normalizedManifestVersion)
        || !java.util.Set.of(
                "ALL", "DRAFT", "VALIDATED", "PUBLISHING", "PUBLISHED", "UNLISTED", "REVOKED")
            .contains(normalizedReleaseState)
        || !java.util.Set.of("ALL", "PENDING", "APPROVED").contains(normalizedApprovalState)
        || !java.util.Set.of("ALL", "SIGNED", "UNSIGNED").contains(normalizedSigned)
        || !normalizedCountry.matches("ALL|[A-Z]{2}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid plugin catalog filter.");
    }
    String from =
        """
        FROM marketplace_plugins p
        LEFT JOIN marketplace_listing_revisions lr ON lr.revision_id=p.effective_listing_revision_id AND lr.approval_state='APPROVED'
        LEFT JOIN LATERAL (SELECT r.metadata FROM marketplace_releases r WHERE r.plugin_id=p.plugin_id AND r.publication_state IN ('PUBLISHED','UNLISTED') ORDER BY marketplace_semver_sort_key(r.version) DESC,r.version COLLATE "C" DESC LIMIT 1) latest ON true
        CROSS JOIN LATERAL (SELECT coalesce(lr.metadata,latest.metadata,'{}'::jsonb) AS metadata) listing
        WHERE (? = '' OR concat_ws(' ',p.plugin_id,p.slug,p.vendor,listing.metadata::text) ILIKE ?)
          AND (? = 'ALL' OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(coalesce(listing.metadata->'categories','[]'::jsonb)) category(value) WHERE category.value = ?))
          AND (? = 'ALL' OR p.moderation_state = ?)
          AND (? = 'ALL' OR EXISTS (
            SELECT 1 FROM marketplace_releases r
            CROSS JOIN LATERAL (SELECT coalesce(r.manifest->'packageManifest',r.manifest) AS runtime_manifest) runtime
            WHERE r.plugin_id=p.plugin_id
              AND (? = 'ALL' OR coalesce(runtime.runtime_manifest->>'manifestVersion','1') = ?)
              AND (? = '' OR (
                marketplace_semver_compare(?,coalesce(runtime.runtime_manifest->>'minimumCoreVersion','0.0.0')) >= 0
                AND (runtime.runtime_manifest->>'maximumCoreVersion' IS NULL OR marketplace_semver_compare(?,runtime.runtime_manifest->>'maximumCoreVersion') <= 0)
              ))
              AND (? = 'ALL' OR coalesce(runtime.runtime_manifest->'geographicAvailability'->>'scope','GLOBAL') = 'GLOBAL'
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(coalesce(runtime.runtime_manifest->'geographicAvailability'->'countries','[]'::jsonb)) allowed(value) WHERE allowed.value = ?))
              AND (? = 'ALL' OR r.publication_state = ?)
              AND (? = 'ALL' OR r.publication_approval_state = ?)
              AND (? = 'ALL'
                OR (? = 'SIGNED' AND EXISTS (SELECT 1 FROM release_signatures signature WHERE signature.release_id=r.release_id))
                OR (? = 'UNSIGNED' AND NOT EXISTS (SELECT 1 FROM release_signatures signature WHERE signature.release_id=r.release_id)))
          ))
        """;
    List<Object> parameters =
        List.of(
            normalizedQuery,
            search,
            normalizedCategory,
            normalizedCategory,
            normalizedModeration,
            normalizedModeration,
            normalizedManifestVersion,
            normalizedManifestVersion,
            normalizedManifestVersion,
            normalizedCoreVersion,
            normalizedCoreVersion,
            normalizedCoreVersion,
            normalizedCountry,
            normalizedCountry,
            normalizedReleaseState,
            normalizedReleaseState,
            normalizedApprovalState,
            normalizedApprovalState,
            normalizedSigned,
            normalizedSigned,
            normalizedSigned);
    Long total = jdbc.queryForObject("SELECT count(*) " + from, Long.class, parameters.toArray());
    List<Map<String, Object>> content =
        jdbc.query(
            """
            SELECT p.plugin_id,p.slug,p.vendor,p.moderation_state,p.moderation_review_reason,
              p.listing_revision_version,p.effective_listing_revision_id::text,
              (SELECT count(*) FROM marketplace_releases r WHERE r.plugin_id=p.plugin_id) AS release_count,
              (SELECT r.version FROM marketplace_releases r WHERE r.plugin_id=p.plugin_id AND r.publication_state='PUBLISHED' ORDER BY marketplace_semver_sort_key(r.version) DESC,r.version COLLATE "C" DESC LIMIT 1) AS newest_published,
              coalesce(listing.metadata->'categories','[]'::jsonb)::text AS categories,
              coalesce(listing.metadata->'title','{}'::jsonb)::text AS title,
              coalesce(listing.metadata->>'author','') AS author,
              coalesce(listing.metadata->'tags','[]'::jsonb)::text AS tags
            """
                + from
                + " ORDER BY p.plugin_id LIMIT ? OFFSET ?",
            (rs, n) ->
                Map.<String, Object>ofEntries(
                    Map.entry("pluginId", rs.getString(1)),
                    Map.entry("slug", rs.getString(2)),
                    Map.entry("vendor", rs.getString(3)),
                    Map.entry("moderationState", rs.getString(4)),
                    Map.entry("moderationReason", rs.getString(5) == null ? "" : rs.getString(5)),
                    Map.entry("listingRevisionVersion", rs.getLong(6)),
                    Map.entry(
                        "effectiveRevisionId", rs.getString(7) == null ? "" : rs.getString(7)),
                    Map.entry("releaseCount", rs.getLong(8)),
                    Map.entry("newestPublished", rs.getString(9) == null ? "" : rs.getString(9)),
                    Map.entry("categories", stringList(rs.getString(10))),
                    Map.entry("title", jsonObject(rs.getString(11))),
                    Map.entry("author", rs.getString(12)),
                    Map.entry("tags", stringList(rs.getString(13)))),
            concat(parameters, List.of((Object) size, (long) page * size)).toArray());
    long elementCount = total == null ? 0 : total;
    return Map.of(
        "content", content,
        "page", page,
        "size", size,
        "totalElements", elementCount,
        "totalPages", (elementCount + size - 1) / size);
  }

  private List<Object> concat(List<Object> first, List<Object> second) {
    java.util.ArrayList<Object> all = new java.util.ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  @GetMapping("/plugins/{pluginId}/releases")
  public List<Map<String, Object>> releases(@PathVariable String pluginId) {
    return jdbc.query(
        "SELECT r.release_id::text,r.version,r.publication_state,r.publication_approval_state,r.manifest_sha256,r.published_at,r.created_at,EXISTS(SELECT 1 FROM release_signatures s WHERE s.release_id=r.release_id) FROM marketplace_releases r WHERE r.plugin_id=? ORDER BY marketplace_semver_sort_key(r.version) DESC,r.version COLLATE \"C\" DESC",
        (rs, n) ->
            Map.<String, Object>ofEntries(
                Map.entry("releaseId", rs.getString(1)),
                Map.entry("version", rs.getString(2)),
                Map.entry("state", rs.getString(3)),
                Map.entry("approvalState", rs.getString(4)),
                Map.entry("manifestSha256", rs.getString(5)),
                Map.entry("signed", rs.getBoolean(8)),
                Map.entry(
                    "publishedAt",
                    rs.getTimestamp(6) == null ? "" : rs.getTimestamp(6).toInstant().toString()),
                Map.entry("createdAt", rs.getTimestamp(7).toInstant().toString())),
        pluginId);
  }

  @GetMapping("/plugins/{pluginId}/revisions")
  public List<Map<String, Object>> revisions(@PathVariable String pluginId) {
    return jdbc.query(
        "SELECT revision_id::text,revision_number,metadata::text,approval_state,created_by,created_at,approved_by,approved_at,approval_reason,expected_listing_version,rollback_source_revision_id::text FROM marketplace_listing_revisions WHERE plugin_id=? ORDER BY revision_number DESC",
        (rs, n) ->
            Map.<String, Object>ofEntries(
                Map.entry("revisionId", rs.getString(1)),
                Map.entry("revisionNumber", rs.getLong(2)),
                Map.entry("metadata", jsonObject(rs.getString(3))),
                Map.entry("approvalState", rs.getString(4)),
                Map.entry("createdBy", rs.getString(5)),
                Map.entry("createdAt", rs.getTimestamp(6).toInstant().toString()),
                Map.entry("approvedBy", rs.getString(7) == null ? "" : rs.getString(7)),
                Map.entry(
                    "approvedAt",
                    rs.getTimestamp(8) == null ? "" : rs.getTimestamp(8).toInstant().toString()),
                Map.entry("approvalReason", rs.getString(9) == null ? "" : rs.getString(9)),
                Map.entry("expectedListingVersion", rs.getLong(10)),
                Map.entry(
                    "rollbackSourceRevisionId", rs.getString(11) == null ? "" : rs.getString(11))),
        pluginId);
  }

  @GetMapping("/plugins/{pluginId}/effective-listing")
  public Map<String, Object> effectiveListing(@PathVariable String pluginId) {
    var rows =
        jdbc.query(
            "SELECT p.listing_revision_version,COALESCE(lr.metadata::text,(SELECT r.metadata::text FROM marketplace_releases r WHERE r.plugin_id=p.plugin_id AND r.publication_state IN ('PUBLISHED','UNLISTED') ORDER BY marketplace_semver_sort_key(r.version) DESC,r.version COLLATE \"C\" DESC LIMIT 1)) FROM marketplace_plugins p LEFT JOIN marketplace_listing_revisions lr ON lr.revision_id=p.effective_listing_revision_id WHERE p.plugin_id=?",
            (rs, n) ->
                Map.of(
                    "version",
                    rs.getLong(1),
                    "metadata",
                    rs.getString(2) == null ? "{}" : rs.getString(2)),
            pluginId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found");
    return Map.of(
        "listingRevisionVersion",
        rows.getFirst().get("version"),
        "metadata",
        jsonObject((String) rows.getFirst().get("metadata")));
  }

  @PostMapping("/plugins/{pluginId}/revisions")
  @Transactional
  public Map<String, Object> createRevision(
      @PathVariable String pluginId, @RequestBody RevisionRequest request) {
    if (request == null || request.metadata() == null || request.expectedListingVersion() < 0)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Metadata and expected listing version are required");
    var versions =
        jdbc.query(
            "SELECT listing_revision_version FROM marketplace_plugins WHERE plugin_id=? FOR UPDATE",
            (rs, n) -> rs.getLong(1),
            pluginId);
    if (versions.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found");
    long current = versions.getFirst();
    if (current != request.expectedListingVersion())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Listing changed since it was loaded; refresh and retry");
    var base =
        jdbc.query(
            "SELECT manifest_document,metadata::text FROM marketplace_releases WHERE plugin_id=? AND publication_state IN ('PUBLISHED','UNLISTED') ORDER BY marketplace_semver_sort_key(version) DESC,version COLLATE \"C\" DESC LIMIT 1",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
            pluginId);
    if (base.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Presentation editing requires a plugin with a published release");
    try {
      ObjectNode candidate = request.metadata().deepCopy();
      JsonNode original = mapper.readTree(base.getFirst()[1]);
      candidate.set("releaseNotes", original.path("releaseNotes").deepCopy());
      long number =
          jdbc.queryForObject(
              "SELECT coalesce(max(revision_number),0)+1 FROM marketplace_listing_revisions WHERE plugin_id=?",
              Long.class,
              pluginId);
      String rollback =
          request.rollbackSourceRevisionId() == null ? "" : request.rollbackSourceRevisionId();
      if (!rollback.isBlank()) {
        Boolean owns =
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM marketplace_listing_revisions WHERE plugin_id=? AND revision_id=?::uuid)",
                Boolean.class,
                pluginId,
                rollback);
        if (!Boolean.TRUE.equals(owns))
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Rollback source must be a revision of this plugin");
        candidate =
            mapper
                .readTree(
                    jdbc.queryForObject(
                        "SELECT metadata::text FROM marketplace_listing_revisions WHERE plugin_id=? AND revision_id=?::uuid",
                        String.class,
                        pluginId,
                        rollback))
                .deepCopy();
        candidate.set("releaseNotes", original.path("releaseNotes").deepCopy());
      }
      var validation = rules.validate(mapper.readTree(base.getFirst()[0]), candidate);
      if (!validation.errors().isEmpty())
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, String.join("; ", validation.errors()));
      String revisionId = java.util.UUID.randomUUID().toString();
      jdbc.update(
          "INSERT INTO marketplace_listing_revisions(revision_id,plugin_id,revision_number,metadata,created_by,expected_listing_version,rollback_source_revision_id) VALUES (?::uuid,?,?,?::jsonb,'local-dev-ui',?,nullif(?,'')::uuid)",
          revisionId,
          pluginId,
          number,
          mapper.writeValueAsString(candidate),
          current,
          rollback);
      auditRevision("CREATE_LISTING_REVISION", pluginId + "#" + number, "metadata validated");
      return Map.of(
          "revisionId",
          revisionId,
          "revisionNumber",
          number,
          "approvalState",
          "PENDING",
          "expectedListingVersion",
          current);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Listing revision is invalid", e);
    }
  }

  @PostMapping("/plugins/{pluginId}/revisions/{revisionId}/activate")
  @Transactional
  public Map<String, Object> activateRevision(
      @PathVariable String pluginId,
      @PathVariable String revisionId,
      @RequestBody(required = false) RevisionApproval request) {
    String reason = request == null ? "" : request.reason();
    if (reason.isBlank() || reason.length() > 1000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An activation reason is required");
    var rows =
        jdbc.query(
            "SELECT expected_listing_version,approval_state FROM marketplace_listing_revisions WHERE plugin_id=? AND revision_id=?::uuid FOR UPDATE",
            (rs, n) -> Map.of("version", rs.getLong(1), "state", rs.getString(2)),
            pluginId,
            revisionId);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing revision not found");
    var row = rows.getFirst();
    if (!"PENDING".equals(row.get("state")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only a pending revision can be activated");
    int approved =
        jdbc.update(
            "UPDATE marketplace_listing_revisions SET approval_state='APPROVED',approved_by='local-dev-ui',approved_at=now(),approval_reason=?,row_version=row_version+1 WHERE plugin_id=? AND revision_id=?::uuid AND approval_state='PENDING'",
            reason,
            pluginId,
            revisionId);
    if (approved != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Revision approval changed concurrently");
    int activated =
        jdbc.update(
            "UPDATE marketplace_plugins SET effective_listing_revision_id=?::uuid,listing_revision_version=listing_revision_version+1,updated_at=now() WHERE plugin_id=? AND listing_revision_version=?",
            revisionId,
            pluginId,
            row.get("version"));
    if (activated != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Listing changed concurrently; create a new revision from the latest version");
    auditRevision("ACTIVATE_LISTING_REVISION", pluginId + "#" + revisionId, reason);
    return Map.of(
        "pluginId",
        pluginId,
        "effectiveRevisionId",
        revisionId,
        "listingRevisionVersion",
        ((Number) row.get("version")).longValue() + 1,
        "approvalState",
        "APPROVED");
  }

  @GetMapping("/releases/{releaseId}")
  public Map<String, Object> release(@PathVariable String releaseId) {
    return publisher.reviewDraft(releaseId, credential.authorization());
  }

  @PostMapping("/releases/validate")
  public Map<String, Object> validate(@RequestBody PublisherController.Submission request) {
    return publisher.validate(request, credential.authorization());
  }

  @PostMapping("/releases")
  public Map<String, Object> create(
      @RequestBody PublisherController.Submission request,
      @RequestHeader("Idempotency-Key") String key) {
    var response = publisher.create(request, credential.authorization(), key);
    return response.getBody();
  }

  @PostMapping(
      value = "/releases/{releaseId}/assets",
      consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> uploadAsset(
      @PathVariable String releaseId,
      @org.springframework.web.bind.annotation.RequestParam("file")
          org.springframework.web.multipart.MultipartFile file,
      @org.springframework.web.bind.annotation.RequestParam("kind") String kind) {
    return publisher.uploadAsset(releaseId, file, kind, credential.authorization());
  }

  @PutMapping("/releases/{releaseId}/catalog")
  public Map<String, Object> catalog(@PathVariable String releaseId, @RequestBody JsonNode body) {
    return publisher.replaceCatalog(releaseId, body, credential.authorization());
  }

  @PutMapping("/releases/{releaseId}/manifest")
  public Map<String, Object> manifest(
      @PathVariable String releaseId, @RequestBody PublisherController.ManifestEdit request) {
    return publisher.replaceDraftManifest(releaseId, request, credential.authorization());
  }

  @PostMapping("/releases/{releaseId}/approve")
  public Map<String, Object> approve(
      @PathVariable String releaseId, @RequestBody PublisherController.ApprovalRequest request) {
    return publisher.approve(releaseId, request, credential.authorization());
  }

  @PostMapping("/releases/{releaseId}/publish")
  public Map<String, Object> publish(@PathVariable String releaseId) {
    var result = publisher.publish(releaseId, credential.authorization());
    signer.signPublishedRelease(releaseId);
    var signed = new java.util.LinkedHashMap<String, Object>(result);
    signed.put("signatureState", "SIGNED");
    return Map.copyOf(signed);
  }

  @PostMapping("/plugins/{pluginId}/moderation")
  public Map<String, Object> moderate(
      @PathVariable String pluginId, @RequestBody PublisherController.ModerationRequest request) {
    return publisher.moderatePlugin(pluginId, request, credential.authorization());
  }

  @PostMapping("/releases/{releaseId}/unlist")
  public Map<String, Object> unlist(@PathVariable String releaseId) {
    return publisher.unlist(releaseId, credential.authorization());
  }

  @PostMapping("/releases/{releaseId}/revoke")
  public Map<String, Object> revoke(
      @PathVariable String releaseId, @RequestBody PublisherController.RevocationRequest request) {
    return publisher.revoke(releaseId, request, credential.authorization());
  }

  @DeleteMapping("/plugins/{pluginId}")
  @Transactional
  public Map<String, Object> deleteEmptyPlugin(@PathVariable String pluginId) {
    var exists =
        jdbc.query(
            "SELECT plugin_id FROM marketplace_plugins WHERE plugin_id=? FOR UPDATE",
            (rs, n) -> rs.getString(1),
            pluginId);
    if (exists.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found");
    Boolean referenced =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM marketplace_releases WHERE plugin_id=?) OR EXISTS(SELECT 1 FROM marketplace_listing_revisions WHERE plugin_id=?) OR EXISTS(SELECT 1 FROM marketplace_release_advisory_events WHERE plugin_id=?)",
            Boolean.class,
            pluginId,
            pluginId,
            pluginId);
    if (Boolean.TRUE.equals(referenced))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Plugin has release, revision, or advisory history and cannot be physically deleted");
    jdbc.update("DELETE FROM marketplace_plugins WHERE plugin_id=?", pluginId);
    auditRevision("DELETE_EMPTY_PLUGIN", pluginId, "empty plugin identity removed");
    return Map.of("deleted", true, "pluginId", pluginId);
  }

  @DeleteMapping("/releases/{releaseId}")
  @Transactional
  public Map<String, Object> deleteDraft(@PathVariable String releaseId) {
    var rows =
        jdbc.query(
            "SELECT plugin_id,version,publication_state,publication_approval_state FROM marketplace_releases WHERE release_id=?::uuid FOR UPDATE",
            (rs, n) ->
                Map.of(
                    "pluginId",
                    rs.getString(1),
                    "version",
                    rs.getString(2),
                    "state",
                    rs.getString(3),
                    "approval",
                    rs.getString(4)),
            releaseId);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Release not found");
    var r = rows.getFirst();
    if (!List.of("DRAFT", "VALIDATED").contains(r.get("state"))
        || !"PENDING".equals(r.get("approval")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only never-approved draft releases can be deleted");
    Boolean signed =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM release_signatures WHERE release_id=?::uuid) OR EXISTS(SELECT 1 FROM publication_promotions WHERE release_id=?::uuid) OR EXISTS(SELECT 1 FROM marketplace_release_advisory_events WHERE release_id=?::uuid)",
            Boolean.class,
            releaseId,
            releaseId,
            releaseId);
    if (Boolean.TRUE.equals(signed))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Release has signature, promotion, or advisory references");
    jdbc.update("DELETE FROM marketplace_releases WHERE release_id=?::uuid", releaseId);
    jdbc.update(
        "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('local-dev-ui','DELETE_DRAFT',?,'OK','{}'::jsonb)",
        r.get("pluginId") + "@" + r.get("version"));
    return Map.of("deleted", true, "releaseId", releaseId);
  }

  private Map<String, Object> jsonObject(String text) {
    try {
      return mapper.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Stored JSON is invalid", e);
    }
  }

  private List<String> stringList(String json) {
    try {
      var value = mapper.readTree(json);
      if (!value.isArray()) return List.of();
      var result = new java.util.ArrayList<String>();
      value.forEach(item -> result.add(item.asText()));
      return List.copyOf(result);
    } catch (Exception e) {
      throw new IllegalStateException("Stored category list is invalid", e);
    }
  }

  private void auditRevision(String action, String target, String reason) {
    try {
      jdbc.update(
          "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('local-dev-ui',?,?,'OK',?::jsonb)",
          action,
          target,
          mapper.writeValueAsString(Map.of("reason", reason)));
    } catch (Exception e) {
      throw new IllegalStateException("Could not write local listing audit", e);
    }
  }

  record RevisionRequest(
      JsonNode metadata, long expectedListingVersion, String rollbackSourceRevisionId) {}

  record RevisionApproval(String reason) {}
}
