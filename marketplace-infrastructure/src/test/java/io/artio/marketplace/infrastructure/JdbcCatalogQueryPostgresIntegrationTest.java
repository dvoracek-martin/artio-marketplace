package io.artio.marketplace.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the real catalog adapter and single current V1 schema on PostgreSQL 16. */
@Testcontainers
class JdbcCatalogQueryPostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("marketplace")
          .withUsername("marketplace")
          .withPassword("marketplace");

  private JdbcTemplate jdbc;
  private JdbcCatalogQuery catalog;

  @BeforeEach
  void migrateAndClear() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("TRUNCATE marketplace_plugins CASCADE");
    catalog = new JdbcCatalogQuery(jdbc, new ObjectMapper());
  }

  @Test
  void catalogChoosesHighestEligibleSemverAndKeepsRevocationReleaseScoped() {
    plugin("com.ex.a", "a");
    release("com.ex.a", "1.0.0", "1.0.0", "1.9.9", "2026-01-01T00:00:00Z", "PUBLISHED");
    release("com.ex.a", "2.0.0", "2.0.0", "2.9.9", "2026-02-01T00:00:00Z", "PUBLISHED");

    var compatible = catalog.plugins(null, null, null, "1.5.0", false, "en", 0, 20);
    assertEquals(1, compatible.totalElements());
    assertEquals("1.0.0", compatible.content().getFirst().get("version"));

    plugin("com.ex.b", "b");
    release("com.ex.b", "2.0.0", "1.0.0", "9.9.9", "2026-03-01T00:00:00Z", "PUBLISHED");
    release("com.ex.b", "1.0.1", "1.0.0", "9.9.9", "2026-04-01T00:00:00Z", "PUBLISHED");

    var newest = catalog.plugins("com.ex.b", null, null, null, false, "en", 0, 20);
    assertEquals("2.0.0", cardVersion(newest, "com.ex.b"));
    List<String> versions =
        catalog.releases("com.ex.b", null, null, false, "en", 0, 20).content().stream()
            .map(release -> (String) release.get("version"))
            .toList();
    assertEquals(List.of("2.0.0", "1.0.1"), versions);

    plugin("com.ex.c", "c");
    release("com.ex.c", "1.0.0", "1.0.0", "9.9.9", "2026-01-01T00:00:00Z", "PUBLISHED");
    release("com.ex.c", "1.1.0", "1.0.0", "9.9.9", "2026-02-01T00:00:00Z", "REVOKED");

    var afterRevoke = catalog.plugins("com.ex.c", null, null, null, false, "en", 0, 20);
    assertEquals("1.0.0", cardVersion(afterRevoke, "com.ex.c"));

    release("com.ex.c", "1.2.0", "1.0.0", "9.9.9", "2026-03-01T00:00:00Z", "PUBLISHED");
    var afterNewRelease = catalog.plugins("com.ex.c", null, null, null, false, "en", 0, 20);
    assertEquals("1.2.0", cardVersion(afterNewRelease, "com.ex.c"));

    jdbc.update(
        "UPDATE marketplace_plugins SET moderation_state='BANNED' WHERE plugin_id='com.ex.c'");
    release("com.ex.c", "1.3.0", "1.0.0", "9.9.9", "2026-04-01T00:00:00Z", "PUBLISHED");
    var banned = catalog.plugins("com.ex.c", null, null, null, false, "en", 0, 20);
    assertTrue(
        banned.content().stream().noneMatch(card -> card.get("pluginId").equals("com.ex.c")),
        "publishing a release must not reactivate plugin moderation");
  }

  @Test
  void catalogAppliesV2NestedCoreAndGeographicCompatibility() {
    plugin("com.ex.v2", "v2");
    String packageManifest =
        """
        {"manifestVersion":"2","id":"com.ex.v2","name":"v2","vendor":"test","version":"1.0.0","description":"v2 fixture","minimumCoreVersion":"1.4.0","maximumCoreVersion":"2.0.0","image":"localhost:5000/public/plugins/v2@sha256:%s","healthPath":"/health","diagnosticsPath":"/diagnostics","geographicAvailability":{"scope":"COUNTRY_SPECIFIC","countries":["CH"]},"dependencies":[{"pluginId":"com.ex.dep","minimumVersion":"1.0.0"}]}
        """
            .formatted("a".repeat(64));
    String manifest =
        """
        {"packageManifest":%s,"capabilities":[],"subscriptions":[]}
        """
            .formatted(packageManifest);
    release("com.ex.v2", "1.0.0", "1.0.0", "9.9.9", "2026-01-01T00:00:00Z", "PUBLISHED", manifest);

    var swiss = catalog.plugins("com.ex.v2", null, "CH", "1.5.0", false, "en", 0, 20);
    assertEquals(1, swiss.totalElements());
    var compatibility = (java.util.Map<?, ?>) swiss.content().getFirst().get("compatibility");
    assertEquals("1.4.0", compatibility.get("minimumCoreVersion"));
    assertEquals("2.0.0", compatibility.get("maximumCoreVersion"));
    assertEquals(1, ((List<?>) compatibility.get("requiredDependencies")).size());

    assertEquals(
        0, catalog.plugins("com.ex.v2", null, "US", "1.5.0", false, "en", 0, 20).totalElements());
    assertEquals(
        0, catalog.plugins("com.ex.v2", null, "CH", "2.1.0", false, "en", 0, 20).totalElements());
  }

  @Test
  void approvedPresentationRevisionOverlaysCatalogButPreservesReleaseNotesSnapshot() {
    plugin("com.ex.revision", "revision");
    release("com.ex.revision", "1.0.0", "1.0.0", "9.9.9", "2026-01-01T00:00:00Z", "PUBLISHED");
    String metadata =
        """
        {"schemaVersion":1,"categories":["other"],"title":{"en":"Rebranded plugin"},"tagline":{"en":"Updated tagline"},"releaseNotes":{"en":"must not replace release snapshot"}}
        """;
    String revisionId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO marketplace_listing_revisions(revision_id,plugin_id,revision_number,metadata,approval_state,created_by,expected_listing_version,approved_by,approved_at,approval_reason) VALUES (?::uuid,?,1,?::jsonb,'APPROVED','admin',0,'admin',now(),'approved listing copy')",
        revisionId,
        "com.ex.revision",
        metadata);
    jdbc.update(
        "UPDATE marketplace_plugins SET effective_listing_revision_id=?::uuid,listing_revision_version=1 WHERE plugin_id='com.ex.revision'",
        revisionId);

    var catalogCard =
        catalog
            .plugins("com.ex.revision", null, null, null, false, "en", 0, 20)
            .content()
            .getFirst();
    @SuppressWarnings("unchecked")
    var publicMetadata = (java.util.Map<String, Object>) catalogCard.get("metadata");
    assertEquals("Rebranded plugin", publicMetadata.get("title"));
    assertEquals(
        "test",
        catalogCard.get("releaseNotes"),
        "release notes must remain the immutable original release snapshot");
  }

  private String cardVersion(
      io.artio.marketplace.application.CatalogQuery.Page page, String pluginId) {
    return page.content().stream()
        .filter(card -> pluginId.equals(card.get("pluginId")))
        .map(card -> (String) card.get("version"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Catalog omitted " + pluginId));
  }

  private void plugin(String id, String slug) {
    jdbc.update(
        "INSERT INTO marketplace_plugins(plugin_id,slug,vendor) VALUES (?,?,?)", id, slug, "test");
  }

  private void release(
      String pluginId,
      String version,
      String minimumCore,
      String maximumCore,
      String publishedAt,
      String state) {
    release(pluginId, version, minimumCore, maximumCore, publishedAt, state, null);
  }

  private void release(
      String pluginId,
      String version,
      String minimumCore,
      String maximumCore,
      String publishedAt,
      String state,
      String suppliedManifest) {
    String manifest =
        suppliedManifest == null
            ? "{\"schemaVersion\":1,\"pluginId\":\""
                + pluginId
                + "\",\"version\":\""
                + version
                + "\",\"minimumCoreVersion\":\""
                + minimumCore
                + "\",\"maximumCoreVersion\":\""
                + maximumCore
                + "\",\"geographicAvailability\":{\"scope\":\"GLOBAL\"},\"dependencies\":[]}"
            : suppliedManifest;
    String metadata =
        "{\"schemaVersion\":1,\"categories\":[\"other\"],\"title\":{\"en\":\""
            + pluginId
            + "\"},\"releaseNotes\":{\"en\":\"test\"}}";
    String hash = "0".repeat(64);
    var timestamp = OffsetDateTime.ofInstant(java.time.Instant.parse(publishedAt), ZoneOffset.UTC);
    jdbc.update(
        """
        INSERT INTO marketplace_releases(
          release_id,plugin_id,version,manifest,manifest_document,manifest_sha256,metadata,
          publication_state,create_request_sha256,published_at,revoked_at,revocation_category,
          revocation_reason,revoked_by)
        VALUES (?::uuid,?,?,?::jsonb,?,?,?::jsonb,?,?,?,
          CASE WHEN ?='REVOKED' THEN ? ELSE NULL END,
          CASE WHEN ?='REVOKED' THEN 'SECURITY' ELSE NULL END,
          CASE WHEN ?='REVOKED' THEN 'test revocation' ELSE NULL END,
          CASE WHEN ?='REVOKED' THEN 'test' ELSE NULL END)
        """,
        UUID.randomUUID().toString(),
        pluginId,
        version,
        manifest,
        manifest,
        hash,
        metadata,
        state,
        hash,
        timestamp,
        state,
        timestamp,
        state,
        state,
        state);
  }
}
