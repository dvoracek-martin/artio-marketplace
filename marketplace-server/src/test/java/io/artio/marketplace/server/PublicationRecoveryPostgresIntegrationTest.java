package io.artio.marketplace.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import io.artio.marketplace.application.ArtifactPromoter;
import io.artio.marketplace.application.ArtifactVerifier;
import io.artio.marketplace.infrastructure.MediaStore;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises promotion recovery and moderation races against the one current V1 schema. */
@Testcontainers(disabledWithoutDocker = true)
class PublicationRecoveryPostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("marketplace")
          .withUsername("marketplace")
          .withPassword("marketplace");

  private static final String PUBLISHER_TOKEN = "publication-test-publisher";
  private static final String ADMIN_TOKEN = "publication-test-admin";
  private static final String RELEASE_ID = "10000000-0000-0000-0000-000000000001";
  private static final String PLUGIN_ID = "recovery.test";
  private static final String MANIFEST =
      """
      {"id":"recovery.test","name":"Recovery test","version":"1.0.0","vendor":"Artio","minimumCoreVersion":"1.0.0","maximumCoreVersion":"2.0.0","image":"localhost:5000/public/plugins/recovery@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","extensionPoints":["catalog.products.read.v1"],"configuration":{},"dependencies":[],"geographicAvailability":{"scope":"GLOBAL","countries":[]}}
      """;
  private static final String CATALOG =
      """
      {"slug":"recovery-test","title":{"en":"Recovery test"},"tagline":{"en":"Test"},"description":{"en":"Test release"},"categories":["other"],"icon":"recovery-icon","screenshots":[],"releaseNotes":{"en":"Initial release"}}
      """;

  private JdbcTemplate jdbc;
  private PublisherController controller;
  private ArtifactPromoter promoter;

  @BeforeEach
  void migrateAndCreateApprovedRelease() throws Exception {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("TRUNCATE marketplace_plugins CASCADE");
    jdbc.execute("TRUNCATE marketplace_media_assets CASCADE");
    jdbc.update(
        "INSERT INTO marketplace_plugins(plugin_id,slug,vendor) VALUES (?,?,?)",
        PLUGIN_ID,
        "recovery-test",
        "Artio");
    jdbc.update(
        "INSERT INTO marketplace_media_assets(asset_id,sha256,relative_path,content_type,byte_size,width,height) VALUES (?,?,?,?,?,?,?)",
        "recovery-icon",
        "b".repeat(64),
        "recovery/icon.png",
        "image/png",
        1,
        1,
        1);
    String manifestDigest = sha(MANIFEST);
    jdbc.update(
        "INSERT INTO marketplace_releases(release_id,plugin_id,version,manifest,manifest_document,manifest_sha256,metadata,publication_state,create_request_sha256) "
            + "VALUES (?::uuid,?,?,?::jsonb,?,?,?::jsonb,'VALIDATED',?)",
        RELEASE_ID,
        PLUGIN_ID,
        "1.0.0",
        MANIFEST,
        MANIFEST,
        manifestDigest,
        CATALOG,
        sha(MANIFEST + "\n" + CATALOG));
    String catalogDigest =
        jdbc.queryForObject(
            "SELECT metadata::text FROM marketplace_releases WHERE release_id=?::uuid",
            String.class,
            RELEASE_ID);
    catalogDigest = sha(catalogDigest);
    jdbc.update(
        "UPDATE marketplace_releases SET publication_approval_state='APPROVED',publication_approved_by='admin',publication_approved_at=now(),approved_manifest_sha256=?,approved_catalog_sha256=? WHERE release_id=?::uuid",
        manifestDigest,
        catalogDigest,
        RELEASE_ID);

    var rules = mock(ManifestRules.class);
    when(rules.registryHost()).thenReturn("localhost:5000");
    when(rules.validate(
            org.mockito.ArgumentMatchers.any(JsonNode.class),
            org.mockito.ArgumentMatchers.any(JsonNode.class)))
        .thenReturn(new ManifestRules.Validation(null, List.of()));
    var artifacts = mock(ArtifactVerifier.class);
    when(artifacts.verify(anyString(), anyString())).thenReturn(List.of());
    when(artifacts.verifyPublished(anyString(), anyString())).thenReturn(List.of());
    promoter = mock(ArtifactPromoter.class);
    controller =
        new PublisherController(
            jdbc,
            new ObjectMapper(),
            rules,
            artifacts,
            promoter,
            mock(MediaStore.class),
            new DataSourceTransactionManager(dataSource),
            mock(SecurityAdvisoryOperations.class),
            sha(PUBLISHER_TOKEN),
            sha(ADMIN_TOKEN),
            sha("publication-test-revocation"));
  }

  @Test
  void devAdminPluginPageSearchesAndPaginatesAgainstPostgres() {
    jdbc.update(
        "INSERT INTO marketplace_plugins(plugin_id,slug,vendor) VALUES (?,?,?)",
        "zzz.other.test",
        "recovery-test-2",
        "Artio");
    var admin =
        new DevAdminController(
            jdbc,
            null,
            null,
            null,
            null,
            new ObjectMapper(),
            "http://127.0.0.1:5000",
            "localhost:5000");

    var firstPage = admin.pluginPage(0, 1, PLUGIN_ID, "ALL", "ACTIVE");
    assertThat(firstPage)
        .containsEntry("page", 0)
        .containsEntry("size", 1)
        .containsEntry("totalElements", 1L);
    assertThat(firstPage.get("totalPages")).isEqualTo(1L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) firstPage.get("content");
    assertThat(content)
        .singleElement()
        .satisfies(plugin -> assertThat(plugin.get("pluginId")).isEqualTo(PLUGIN_ID));

    var nextPage = admin.pluginPage(1, 1, "", "ALL", "ACTIVE");
    assertThat(nextPage).containsEntry("page", 1).containsEntry("totalElements", 2L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> secondPage = (List<Map<String, Object>>) nextPage.get("content");
    assertThat(secondPage)
        .singleElement()
        .satisfies(plugin -> assertThat(plugin.get("pluginId")).isEqualTo("zzz.other.test"));
    assertThatThrownBy(() -> admin.pluginPage(0, 101, "", "ALL", "ALL"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void partialCopyFailureLeavesRetryableJobAndRetryPublishesTheSameApprovedRelease() {
    doAnswer(
            invocation -> {
              throw new IllegalStateException(
                  "simulated process interruption after partial OCI copy");
            })
        .doNothing()
        .when(promoter)
        .promote(anyString());

    assertThatThrownBy(() -> controller.publish(RELEASE_ID, "Bearer " + PUBLISHER_TOKEN))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Approved OCI promotion failed; retry is safe");
    assertRelease("VALIDATED", "RETRYABLE", "PROMOTION_INCOMPLETE", 1);

    Map<String, Object> result = controller.publish(RELEASE_ID, "Bearer " + PUBLISHER_TOKEN);

    assertThat(result).containsEntry("state", "PUBLISHED");
    assertRelease("PUBLISHED", "PUBLISHED", null, 2);
  }

  @Test
  void expiredPromotionLeaseRecoversToRetryableAndCanBePublishedAgain() {
    jdbc.update(
        "UPDATE marketplace_releases SET publication_state='PUBLISHING',publishing_started_at=now()-interval '16 minutes',row_version=row_version+1 WHERE release_id=?::uuid",
        RELEASE_ID);
    jdbc.update(
        "INSERT INTO publication_promotions(release_id,state,attempt_count,started_at,updated_at) VALUES (?::uuid,'PROMOTING',1,now()-interval '16 minutes',now()-interval '16 minutes')",
        RELEASE_ID);

    new PublicationReservationRecovery(jdbc).recoverExpiredReservations();

    assertRelease("VALIDATED", "RETRYABLE", "PROMOTION_LEASE_EXPIRED", 1);
    assertThat(controller.publish(RELEASE_ID, "Bearer " + PUBLISHER_TOKEN))
        .containsEntry("state", "PUBLISHED");
    assertRelease("PUBLISHED", "PUBLISHED", null, 2);
  }

  @Test
  void moderationDuringPromotionPreventsFinalizationAndKeepsRetrySafeState() {
    doAnswer(
            invocation -> {
              jdbc.update(
                  "UPDATE marketplace_plugins SET moderation_state='SUSPENDED' WHERE plugin_id=?",
                  PLUGIN_ID);
              return null;
            })
        .when(promoter)
        .promote(anyString());

    assertThatThrownBy(() -> controller.publish(RELEASE_ID, "Bearer " + PUBLISHER_TOKEN))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Release changed while publishing; retry");
    assertRelease("VALIDATED", "RETRYABLE", "PROMOTION_INCOMPLETE", 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT moderation_state FROM marketplace_plugins WHERE plugin_id=?",
                String.class,
                PLUGIN_ID))
        .isEqualTo("SUSPENDED");

    jdbc.update(
        "UPDATE marketplace_plugins SET moderation_state='ACTIVE' WHERE plugin_id=?", PLUGIN_ID);
    org.mockito.Mockito.doNothing().when(promoter).promote(anyString());
    assertThat(controller.publish(RELEASE_ID, "Bearer " + PUBLISHER_TOKEN))
        .containsEntry("state", "PUBLISHED");
    assertRelease("PUBLISHED", "PUBLISHED", null, 2);
  }

  @Test
  void releaseSigningKeyRotationSupportsOverlapThenRejectsRetiredKey() throws Exception {
    jdbc.update(
        "UPDATE marketplace_releases SET publication_state='PUBLISHED',published_at=now() WHERE release_id=?::uuid",
        RELEASE_ID);
    ObjectMapper mapper = new ObjectMapper();
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair oldKey = generator.generateKeyPair();
    KeyPair newKey = generator.generateKeyPair();
    String oldPublicKey = encodePublicKey(oldKey);
    String newPublicKey = encodePublicKey(newKey);

    var beforeRotation =
        new ReleaseDescriptorService(
            jdbc, mapper, mock(SecurityAdvisoryOperations.class), "release-old=" + oldPublicKey);
    JsonNode descriptor = beforeRotation.descriptor(RELEASE_ID);
    ObjectNode oldEnvelope = signEnvelope(mapper, descriptor, "release-old", oldKey);
    beforeRotation.verifyAndStore(RELEASE_ID, oldEnvelope);
    assertThat(beforeRotation.signature(RELEASE_ID).get("signature"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("keyId", "release-old");

    var overlap =
        new ReleaseDescriptorService(
            jdbc,
            mapper,
            mock(SecurityAdvisoryOperations.class),
            "release-old=" + oldPublicKey + ",release-new=" + newPublicKey);
    ObjectNode newEnvelope = signEnvelope(mapper, descriptor, "release-new", newKey);
    jdbc.execute("SELECT pg_sleep(0.01)");
    overlap.verifyAndStore(RELEASE_ID, newEnvelope);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM release_signatures WHERE release_id=?::uuid",
                Integer.class,
                RELEASE_ID))
        .isEqualTo(2);
    assertThat(overlap.signature(RELEASE_ID).get("signature"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("keyId", "release-new");

    var afterRetirement =
        new ReleaseDescriptorService(
            jdbc, mapper, mock(SecurityAdvisoryOperations.class), "release-new=" + newPublicKey);
    assertThatThrownBy(() -> afterRetirement.verifyAndStore(RELEASE_ID, oldEnvelope))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Signing key is not trusted by Marketplace");
    assertThat(afterRetirement.signature(RELEASE_ID).get("signature"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("keyId", "release-new");
  }

  private void assertRelease(
      String releaseState, String promotionState, String error, int attempts) {
    assertThat(
            jdbc.queryForObject(
                "SELECT publication_state FROM marketplace_releases WHERE release_id=?::uuid",
                String.class,
                RELEASE_ID))
        .isEqualTo(releaseState);
    var promotion =
        jdbc.queryForMap(
            "SELECT state,last_error_code,attempt_count FROM publication_promotions WHERE release_id=?::uuid",
            RELEASE_ID);
    assertThat(promotion.get("state")).isEqualTo(promotionState);
    assertThat(promotion.get("last_error_code")).isEqualTo(error);
    assertThat(promotion.get("attempt_count")).isEqualTo(attempts);
  }

  private static String sha(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static String encodePublicKey(KeyPair keyPair) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.getPublic().getEncoded());
  }

  private static ObjectNode signEnvelope(
      ObjectMapper mapper, JsonNode descriptor, String keyId, KeyPair keyPair) throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keyPair.getPrivate());
    signer.update(
        ReleaseDescriptorCanonicalizer.canonicalBytes(mapper.writeValueAsString(descriptor)));
    String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    ObjectNode envelope = mapper.createObjectNode();
    envelope.set("descriptor", descriptor.deepCopy());
    envelope
        .putObject("signature")
        .put("algorithm", "Ed25519")
        .put("keyId", keyId)
        .put("value", signature);
    return envelope;
  }
}
