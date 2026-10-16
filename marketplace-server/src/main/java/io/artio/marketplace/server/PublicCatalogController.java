package io.artio.marketplace.server;

import io.artio.marketplace.application.CatalogQuery;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/marketplace")
public class PublicCatalogController {
  private final CatalogQuery catalog;
  private final JdbcTemplate jdbc;
  private final io.artio.marketplace.infrastructure.MediaStore media;

  public PublicCatalogController(
      CatalogQuery catalog,
      JdbcTemplate jdbc,
      io.artio.marketplace.infrastructure.MediaStore media) {
    this.catalog = catalog;
    this.jdbc = jdbc;
    this.media = media;
  }

  @GetMapping("/plugins")
  public CatalogQuery.Page plugins(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String country,
      @RequestParam(required = false) String coreVersion,
      @RequestParam(defaultValue = "false") boolean includePrerelease,
      @RequestParam(defaultValue = "en") String locale,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return catalog.plugins(
        q, category, country, coreVersion, includePrerelease, locale, page, size);
  }

  @GetMapping("/plugins/{pluginId}")
  public Map<String, Object> plugin(
      @PathVariable String pluginId,
      @RequestParam(required = false) String coreVersion,
      @RequestParam(required = false) String country,
      @RequestParam(defaultValue = "false") boolean includePrerelease,
      @RequestParam(defaultValue = "en") String locale) {
    return catalog.plugin(pluginId, coreVersion, country, includePrerelease, locale);
  }

  @GetMapping("/plugins/{pluginId}/releases")
  public CatalogQuery.Page releases(
      @PathVariable String pluginId,
      @RequestParam(required = false) String coreVersion,
      @RequestParam(required = false) String country,
      @RequestParam(defaultValue = "false") boolean includePrerelease,
      @RequestParam(defaultValue = "en") String locale,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return catalog.releases(pluginId, coreVersion, country, includePrerelease, locale, page, size);
  }

  @GetMapping("/plugins/{pluginId}/releases/{version}")
  public Map<String, Object> release(
      @PathVariable String pluginId,
      @PathVariable String version,
      @RequestParam(required = false) String coreVersion,
      @RequestParam(required = false) String country,
      @RequestParam(defaultValue = "false") boolean includePrerelease,
      @RequestParam(defaultValue = "en") String locale) {
    return catalog.release(pluginId, version, coreVersion, country, includePrerelease, locale);
  }

  @GetMapping("/plugins/{pluginId}/releases/{version}/manifest")
  public Object manifest(
      @PathVariable String pluginId,
      @PathVariable String version,
      @RequestParam(required = false) String coreVersion,
      @RequestParam(required = false) String country,
      @RequestParam(defaultValue = "false") boolean includePrerelease) {
    return catalog
        .release(pluginId, version, coreVersion, country, includePrerelease, "en")
        .get("manifest");
  }

  @GetMapping("/plugins/{pluginId}/latest-compatible")
  public Map<String, Object> latestCompatible(
      @PathVariable String pluginId,
      @RequestParam String coreVersion,
      @RequestParam(required = false) String country,
      @RequestParam(defaultValue = "false") boolean includePrerelease) {
    return catalog.latestCompatible(pluginId, coreVersion, country, includePrerelease);
  }

  @GetMapping("/categories")
  public List<Map<String, Object>> categories(@RequestParam(defaultValue = "en") String locale) {
    String lang = locale.toLowerCase(java.util.Locale.ROOT);
    if (!Set.of("en", "de", "fr", "it").contains(lang))
      throw new IllegalArgumentException("locale must be en, de, fr or it");
    Map<String, Map<String, String>> labels =
        Map.of(
            "analytics",
                Map.of("en", "Analytics", "de", "Analytik", "fr", "Analytique", "it", "Analisi"),
            "catalog",
                Map.of("en", "Catalog", "de", "Katalog", "fr", "Catalogue", "it", "Catalogo"),
            "communication",
                Map.of(
                    "en",
                    "Communication",
                    "de",
                    "Kommunikation",
                    "fr",
                    "Communication",
                    "it",
                    "Comunicazione"),
            "payments",
                Map.of("en", "Payments", "de", "Zahlungen", "fr", "Paiements", "it", "Pagamenti"),
            "shipping",
                Map.of("en", "Shipping", "de", "Versand", "fr", "Expédition", "it", "Spedizione"),
            "security",
                Map.of("en", "Security", "de", "Sicherheit", "fr", "Sécurité", "it", "Sicurezza"),
            "operations",
                Map.of("en", "Operations", "de", "Betrieb", "fr", "Opérations", "it", "Operazioni"),
            "other", Map.of("en", "Other", "de", "Sonstiges", "fr", "Autre", "it", "Altro"));
    return jdbc.query(
        "SELECT DISTINCT c.value #>> '{}' AS code FROM marketplace_plugins p JOIN marketplace_releases r ON r.plugin_id=p.plugin_id CROSS JOIN LATERAL jsonb_array_elements(r.metadata->'categories') c WHERE r.publication_state='PUBLISHED' AND p.moderation_state='ACTIVE' ORDER BY code",
        (rs, n) -> {
          String code = rs.getString("code");
          return Map.of(
              "code", code, "label", labels.getOrDefault(code, labels.get("other")).get(lang));
        });
  }

  @GetMapping("/assets/{assetId}")
  public org.springframework.http.ResponseEntity<byte[]> asset(
      @PathVariable String assetId,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    var value = media.read(assetId);
    String etag = "\"" + value.sha256() + "\"";
    var headers = new HttpHeaders();
    headers.setETag(etag);
    headers.setCacheControl("public, max-age=31536000, immutable");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.setContentType(org.springframework.http.MediaType.parseMediaType(value.contentType()));
    if (ifNoneMatch != null
        && java.util.Arrays.stream(ifNoneMatch.split(","))
            .map(String::trim)
            .anyMatch(
                candidate ->
                    candidate.equals("*")
                        || candidate.equals(etag)
                        || candidate.equals("W/" + etag)))
      return new org.springframework.http.ResponseEntity<>(
          headers, org.springframework.http.HttpStatus.NOT_MODIFIED);
    return new org.springframework.http.ResponseEntity<>(
        value.bytes(), headers, org.springframework.http.HttpStatus.OK);
  }
}
