package io.artio.marketplace.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManifestRulesTest {
  private final ObjectMapper mapper =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private ManifestRules rules;

  @BeforeEach
  void setUp() {
    rules = new ManifestRules(mapper, "localhost:5000");
  }

  @Test
  void acceptsCompatibleCoreTemplatesAndPreservesMultiServiceShape() throws Exception {
    for (String fixture : List.of("stripe", "swiss-post", "observability")) {
      var manifest = fixture(fixture);
      var result = rules.validate(manifest, catalog(manifest.path("id").asText()));
      assertTrue(result.valid(), fixture + " template rejected: " + result.errors());
      int actual = manifest.path("services").isArray() ? manifest.path("services").size() : 0;
      assertEquals(fixture.equals("observability") ? 4 : 0, actual);
    }
  }

  @Test
  void reportsPinnedCoreOpenSearchTemplateValidatorConflict() throws Exception {
    var manifest = fixture("opensearch");
    var result = rules.validate(manifest, catalog(manifest.path("id").asText()));
    assertFalse(result.valid());
    assertTrue(
        result.errors().stream()
            .anyMatch(e -> e.contains("persistent path must be under /data, /var/lib")));
    assertEquals(1, manifest.path("services").size());
  }

  @Test
  void rejectsUnknownRuntimePropertiesAndUntrustedRegistry() throws Exception {
    var unknown = fixture("stripe");
    unknown.put("marketplaceOnlyField", true);
    assertFalse(rules.validate(unknown, catalog(unknown.path("id").asText())).valid());
    var untrusted = fixture("stripe");
    untrusted.put("image", "evil.example/plugin@sha256:" + "a".repeat(64));
    assertTrue(
        rules.validate(untrusted, catalog(untrusted.path("id").asText())).errors().stream()
            .anyMatch(e -> e.contains("trusted")));
  }

  @Test
  void acceptsCoreCapabilityManifestV2WithoutChangingThePublishedEnvelope() throws Exception {
    var manifest = v2Manifest("capability.payment-test");
    var catalog = catalog("capability.payment-test");

    var result = rules.validate(manifest, catalog);

    assertTrue(result.valid(), "v2 manifest rejected: " + result.errors());
    assertEquals("capability.payment-test", result.manifest().id());
    assertEquals("2", manifest.path("packageManifest").path("manifestVersion").asText());
  }

  @Test
  void acceptsV2SecretFileBindingsWithoutChangingTheV1PackageContract() throws Exception {
    var manifest = v2Manifest("capability.payment-test");
    var packageManifest =
        (com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("packageManifest");
    packageManifest
        .with("configuration")
        .putObject("providerSecret")
        .put("type", "secret")
        .put("required", true)
        .put("secret", true)
        .put("label", "Provider secret");
    packageManifest
        .putObject("secretFileBindings")
        .put("/run/secrets/provider-key", "providerSecret");

    var result = rules.validate(manifest, catalog("capability.payment-test"));

    assertTrue(result.valid(), "v2 secret-file manifest rejected: " + result.errors());
    assertEquals("2", manifest.path("packageManifest").path("manifestVersion").asText());
    assertTrue(manifest.path("packageManifest").has("secretFileBindings"));
  }

  @Test
  void acceptsGeneratedSecretFieldsAndDependencyOrderedOneShotJobsInV2() throws Exception {
    var manifest = v2Manifest("capability.payment-test");
    var packageManifest =
        (com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("packageManifest");
    packageManifest
        .with("configuration")
        .putObject("runtimeSecret")
        .put("type", "secret")
        .put("required", true)
        .put("secret", true)
        .put("generated", true)
        .put("generatedLength", 32)
        .put("generatedRotationGroup", "runtime")
        .put("label", "Runtime secret");
    packageManifest.set(
        "services",
        mapper.readTree(
            """
        [
          {"id":"minio","image":"%s","port":9000,"healthPath":"/minio/health/ready",
           "beforePrimary":true,"persistentPaths":["/var/lib/minio/data"]},
          {"id":"provision","image":"%s","port":0,"healthPath":null,
           "beforePrimary":true,"runToCompletion":true,"dependsOn":["minio"]}
        ]
        """
                .formatted(image("minio"), image("minio-provisioner"))));

    var result = rules.validate(manifest, catalog("capability.payment-test"));

    assertTrue(result.valid(), "v2 generated-secret/job manifest rejected: " + result.errors());
    assertTrue(result.manifest().configuration().get("runtimeSecret").generated());
    assertEquals(32, result.manifest().configuration().get("runtimeSecret").generatedLength());
    assertEquals(
        "runtime", result.manifest().configuration().get("runtimeSecret").generatedRotationGroup());
    assertTrue(result.manifest().services().get(1).beforePrimary());
    assertTrue(result.manifest().services().get(1).runToCompletion());
  }

  @Test
  void rejectsGeneratedSecretsAndLifecyclePropertiesInV1() throws Exception {
    var manifest = fixture("stripe");
    manifest
        .with("configuration")
        .putObject("runtimeSecret")
        .put("type", "secret")
        .put("required", true)
        .put("secret", true)
        .put("generated", true)
        .put("generatedLength", 24)
        .put("label", "Runtime secret");
    manifest.set(
        "services",
        mapper.readTree(
            """
        [{"id":"setup","image":"%s","port":0,"healthPath":null,
          "beforePrimary":true,"runToCompletion":true}]
        """
                .formatted(image("setup"))));

    var result = rules.validate(manifest, catalog(manifest.path("id").asText()));

    assertFalse(result.valid());
    assertTrue(result.errors().contains("generated configuration secrets require manifest v2"));
    assertTrue(result.errors().contains("bundle service lifecycle options require manifest v2"));
  }

  @Test
  void rejectsUnsafeV2SecretFileBindings() throws Exception {
    var manifest = v2Manifest("capability.payment-test");
    ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("packageManifest"))
        .putObject("secretFileBindings")
        .put("/run/secrets/../escape", "stripeSecretKey");

    var result = rules.validate(manifest, catalog("capability.payment-test"));

    assertFalse(result.valid());
    assertTrue(result.errors().stream().anyMatch(e -> e.contains("invalid secret file path")));
  }

  @Test
  void rejectsV2SecretFileBindingsToNonSecretConfiguration() throws Exception {
    var manifest = v2Manifest("capability.payment-test");
    var packageManifest =
        (com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("packageManifest");
    packageManifest
        .with("configuration")
        .putObject("ordinaryValue")
        .put("type", "text")
        .put("required", false)
        .put("secret", false)
        .put("label", "Ordinary value");
    packageManifest
        .putObject("secretFileBindings")
        .put("/run/secrets/provider-key", "ordinaryValue");

    var result = rules.validate(manifest, catalog("capability.payment-test"));

    assertFalse(result.valid());
    assertTrue(
        result.errors().stream()
            .anyMatch(e -> e.contains("must reference a declared secret field")));
  }

  @Test
  void rejectsUnsupportedCapabilitiesAndEventsInManifestV2() throws Exception {
    var manifest = v2Manifest("capability.invalid-test");
    ((com.fasterxml.jackson.databind.node.ArrayNode)
            manifest.withArray("capabilities").get(0).get("operations"))
        .add("unknownOperation");
    manifest
        .withArray("subscriptions")
        .addObject()
        .put("eventType", "customer.exported")
        .put("schemaVersion", 1);

    var result = rules.validate(manifest, catalog("capability.invalid-test"));

    assertFalse(result.valid());
    assertTrue(result.errors().stream().anyMatch(e -> e.contains("unsupported operation")));
    assertTrue(
        result.errors().stream().anyMatch(e -> e.contains("unsupported event subscription")));
  }

  @Test
  void rejectsMarkupAndUnsupportedLocalesInCatalogText() throws Exception {
    var manifest = fixture("stripe");
    var catalog = catalog(manifest.path("id").asText());
    catalog.with("description").put("fr", "<script>alert(1)</script>");
    assertTrue(
        rules.validate(manifest, catalog).errors().stream().anyMatch(e -> e.contains("markup")));
    catalog = catalog(manifest.path("id").asText());
    catalog.with("title").put("es", "Plugin");
    assertTrue(
        rules.validate(manifest, catalog).errors().stream()
            .anyMatch(e -> e.contains("supports only")));
  }

  @Test
  void rejectsUnsafeCoreConfigurationLabelsAndOversizedOrDeepTextButKeepsUnicode()
      throws Exception {
    var manifest = fixture("stripe");
    var catalog = catalog(manifest.path("id").asText());
    manifest
        .with("configuration")
        .with("stripeSecretKey")
        .put("label", "<img src=x onerror=alert(1)>");
    assertTrue(
        rules.validate(manifest, catalog).errors().stream().anyMatch(e -> e.contains("markup")));

    manifest = fixture("stripe");
    manifest.put("description", "ééééé Ω plugin");
    catalog.with("title").put("en", "Märkte für Zürich");
    assertTrue(rules.validate(manifest, catalog).valid());

    manifest = fixture("stripe");
    manifest.put("description", "x".repeat(10_001));
    assertTrue(
        rules.validate(manifest, catalog).errors().stream()
            .anyMatch(e -> e.contains("10000 character")));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode fixture(String name) throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/core/" + name + ".template.json")) {
      var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(in);
      manifest.put("image", image(name));
      if (manifest.path("services").isArray())
        for (int i = 0; i < manifest.path("services").size(); i++)
          ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("services").get(i))
              .put("image", image(name + "-sidecar-" + i));
      return manifest;
    }
  }

  private com.fasterxml.jackson.databind.node.ObjectNode catalog(String pluginId) throws Exception {
    return (com.fasterxml.jackson.databind.node.ObjectNode)
        mapper.readTree(
            """
                {"schemaVersion":1,"pluginId":"%s","slug":"fixture-plugin","title":{"en":"Fixture"},
                 "tagline":{"en":"Safe fixture"},"description":{"en":"Fixture description"},
                 "releaseNotes":{"en":"Initial release"},"vendorDisplayName":"Example","author":"Test",
                 "categories":["other"],"tags":["fixture"],"pricingModel":"FREE",
                 "icon":"sha256-%s","screenshots":[]}
                """
                .formatted(pluginId, "0".repeat(64)));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode v2Manifest(String pluginId)
      throws Exception {
    var packageManifest = fixture("stripe");
    packageManifest.put("manifestVersion", "2");
    packageManifest.remove(List.of("extensionPoints", "permissions"));
    var manifest = mapper.createObjectNode();
    manifest.set("packageManifest", packageManifest);
    manifest.set(
        "capabilities",
        mapper.readTree(
            """
                [{"capabilityId":"payment.provider.v1","operations":["createCheckout"],
                  "permissions":["payment.process"],"accountFingerprints":{}}]
                """));
    manifest.set("subscriptions", mapper.createArrayNode());
    packageManifest.put("id", pluginId);
    return manifest;
  }

  private static String image(String repository) {
    return "localhost:5000/public/plugins/" + repository + "@sha256:" + "a".repeat(64);
  }
}
