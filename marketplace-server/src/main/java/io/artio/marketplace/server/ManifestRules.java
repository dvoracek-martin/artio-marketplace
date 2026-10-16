package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.PluginManifestV2;
import io.artio.extensions.PluginManifestValidator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Delegates runtime manifest validation to the exact source snapshot copied from pinned Artio Core.
 */
@Component
public final class ManifestRules {
  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Set<String> CATEGORIES =
      Set.of(
          "analytics",
          "catalog",
          "communication",
          "payments",
          "shipping",
          "security",
          "operations",
          "other");
  private final ObjectMapper mapper;
  private final PluginManifestValidator validator;
  private final String registryHost;

  public ManifestRules(
      ObjectMapper mapper,
      @Value("${marketplace.registry.public-host:localhost:5000}") String registryHost) {
    this.mapper = mapper;
    this.registryHost = registryHost;
    this.validator = new PluginManifestValidator(Set.of(registryHost));
  }

  public Validation validate(JsonNode manifest, JsonNode catalog) {
    var errors = new java.util.ArrayList<String>();
    inspectInput(manifest, "manifest", errors);
    inspectInput(catalog, "catalog", errors);
    PluginManifest parsed = null;
    try {
      if (manifest.has("packageManifest")) {
        PluginManifestV2 parsedV2 = mapper.treeToValue(manifest, PluginManifestV2.class);
        parsed = parsedV2.packageManifest();
        Set<String> declaredDependencies = declaredDependencies(parsed);
        errors.addAll(
            validator.validate(parsedV2, parsed.minimumCoreVersion(), declaredDependencies));
      } else {
        parsed = mapper.treeToValue(manifest, PluginManifest.class);
        Set<String> declaredDependencies = declaredDependencies(parsed);
        errors.addAll(
            validator.validate(parsed, parsed.minimumCoreVersion(), declaredDependencies));
      }
    } catch (Exception ex) {
      errors.add("manifest does not match the supported Core PluginManifest v1/v2 contract");
    }
    validateCatalog(catalog, parsed, errors);
    return new Validation(parsed, errors.stream().distinct().toList());
  }

  private static Set<String> declaredDependencies(PluginManifest manifest) {
    Set<String> dependencies = new HashSet<>();
    manifest.dependencies().forEach(dependency -> dependencies.add(dependency.id()));
    return dependencies;
  }

  private void validateCatalog(JsonNode c, PluginManifest m, List<String> errors) {
    if (!c.isObject()) {
      errors.add("catalog metadata must be an object");
      return;
    }
    Set<String> allowed =
        Set.of(
            "schemaVersion",
            "pluginId",
            "slug",
            "title",
            "tagline",
            "description",
            "vendorDisplayName",
            "author",
            "publisherWebsite",
            "supportUrl",
            "documentationUrl",
            "sourceUrl",
            "licenseName",
            "licenseUrl",
            "pricingModel",
            "categories",
            "tags",
            "icon",
            "screenshots",
            "highlights",
            "releaseNotes");
    c.fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key)) errors.add("unknown catalog property: " + key);
            });
    if (c.path("schemaVersion").asInt(-1) != 1) errors.add("schemaVersion must be 1");
    if (m != null && !c.path("pluginId").asText().equals(m.id()))
      errors.add("catalog pluginId must match the runtime manifest id");
    String slug = c.path("slug").asText("");
    if (!SLUG.matcher(slug).matches() || slug.length() > 120)
      errors.add("slug must be a stable lowercase URL slug");
    if (!c.path("pricingModel").asText().equals("FREE"))
      errors.add("pricingModel must be FREE in v1");
    requiredLocales(c.path("title"), "title", 120, errors);
    requiredLocales(c.path("tagline"), "tagline", 200, errors);
    requiredLocales(c.path("description"), "description", 10000, errors);
    requiredLocales(c.path("releaseNotes"), "releaseNotes", 10000, errors);
    for (String key : List.of("vendorDisplayName", "author"))
      if (c.path(key).asText().isBlank() || c.path(key).asText().length() > 120)
        errors.add(key + " is required and limited to 120 characters");
    JsonNode categories = c.path("categories");
    if (!categories.isArray() || categories.isEmpty() || categories.size() > 5)
      errors.add("categories must contain 1 to 5 known categories");
    else
      categories.forEach(
          n -> {
            if (!CATEGORIES.contains(n.asText())) errors.add("unknown category: " + n.asText());
          });
    JsonNode tags = c.path("tags");
    if (!tags.isArray() || tags.size() > 20) errors.add("tags must contain at most 20 entries");
    else
      tags.forEach(
          n -> {
            if (!n.asText().matches("[a-z0-9][a-z0-9-]{0,39}")) errors.add("invalid tag");
          });
    for (String key :
        List.of("publisherWebsite", "supportUrl", "documentationUrl", "sourceUrl", "licenseUrl")) {
      JsonNode value = c.get(key);
      if (value != null && !value.isNull() && !value.asText().isBlank()) {
        try {
          var u = java.net.URI.create(value.asText());
          if (!"https".equalsIgnoreCase(u.getScheme())
              || u.getHost() == null
              || u.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
          errors.add(key + " must be an absolute HTTPS URL");
        }
      }
    }
    if (c.path("icon").asText().isBlank())
      errors.add("icon must reference an uploaded media asset");
    JsonNode screenshots = c.path("screenshots");
    if (!screenshots.isArray() || screenshots.size() > 10)
      errors.add("screenshots must be an array with at most 10 assets");
    else {
      var orders = new java.util.HashSet<Integer>();
      screenshots.forEach(
          s -> {
            if (s.path("assetId").asText().isBlank()) errors.add("screenshot assetId is required");
            int order = s.path("order").asInt(-1);
            if (order < 0 || !orders.add(order))
              errors.add("screenshot order values must be unique non-negative integers");
            s.fieldNames()
                .forEachRemaining(
                    k -> {
                      if (!Set.of("assetId", "order", "alt", "caption").contains(k))
                        errors.add("unknown screenshot property: " + k);
                    });
            requiredLocales(s.path("alt"), "screenshot alt", 300, errors);
            if (s.has("caption"))
              requiredLocales(s.path("caption"), "screenshot caption", 500, errors);
          });
    }
  }

  private static void inspectInput(JsonNode node, String root, List<String> errors) {
    if (node == null) {
      errors.add(root + " is required");
      return;
    }
    long[] budget = {0, 0};
    inspectText(node, root, root, 0, budget, errors);
    if (budget[0] > 20_000) errors.add(root + " has too many JSON values");
    if (budget[1] > 100_000) errors.add(root + " text exceeds the 100000 character limit");
  }

  private static void inspectText(
      JsonNode node, String root, String path, int depth, long[] budget, List<String> errors) {
    if (depth > 32) {
      errors.add(root + " nesting exceeds 32 levels");
      return;
    }
    budget[0]++;
    if (node.isTextual()) {
      String original = node.asText();
      budget[1] += original.length();
      if (original.length() > 10_000) errors.add(path + " exceeds the 10000 character limit");
      String value = original.toLowerCase(java.util.Locale.ROOT);
      if (value.contains("<")
          || value.contains(">")
          || value.contains("javascript:")
          || value.contains("data:text/html")
          || value.contains("vbscript:")
          || value.contains("data:image/svg+xml")
          || original
              .codePoints()
              .anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t'))
        errors.add(path + " contains markup, control characters or an unsafe URL scheme");
    } else if (node.isContainerNode()) {
      if (node.isObject()) {
        node.fields()
            .forEachRemaining(
                entry -> {
                  budget[1] += entry.getKey().length();
                  inspectText(
                      entry.getValue(),
                      root,
                      path + "." + entry.getKey(),
                      depth + 1,
                      budget,
                      errors);
                });
      } else {
        for (int i = 0; i < node.size(); i++)
          inspectText(node.get(i), root, path + "[" + i + "]", depth + 1, budget, errors);
      }
    }
  }

  private static void requiredLocales(JsonNode node, String field, int max, List<String> errors) {
    if (!node.isObject() || node.path("en").asText().isBlank()) {
      errors.add(field + " requires English text");
      return;
    }
    node.fields()
        .forEachRemaining(
            e -> {
              if (!Set.of("en", "de", "fr", "it").contains(e.getKey()))
                errors.add(field + " supports only en, de, fr and it");
              if (!e.getValue().isTextual() || e.getValue().asText().length() > max)
                errors.add(field + " exceeds its length limit");
            });
  }

  public String registryHost() {
    return registryHost;
  }

  public record Validation(PluginManifest manifest, List<String> errors) {
    public boolean valid() {
      return errors.isEmpty();
    }
  }
}
