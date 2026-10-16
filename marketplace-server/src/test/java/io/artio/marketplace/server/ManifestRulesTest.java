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

  private static String image(String repository) {
    return "localhost:5000/public/plugins/" + repository + "@sha256:" + "a".repeat(64);
  }
}
