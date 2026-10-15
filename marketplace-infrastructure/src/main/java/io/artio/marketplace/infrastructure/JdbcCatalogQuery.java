package io.artio.marketplace.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.marketplace.application.CatalogQuery;
import io.artio.marketplace.domain.SemanticVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class JdbcCatalogQuery implements CatalogQuery {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcCatalogQuery(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public Page plugins(
      String query,
      String category,
      String country,
      String coreVersion,
      String locale,
      int page,
      int size) {
    checkPaging(page, size);
    validateLocale(locale);
    validateCountry(country);
    String term = query == null || query.isBlank() ? null : query.trim();
    if (term != null && term.length() > 100)
      throw new IllegalArgumentException("q must be at most 100 characters");
    if (coreVersion != null) SemanticVersion.parse(coreVersion);
    String where =
        """
                  AND (? IS NULL OR p.plugin_id % ? OR p.slug % ? OR p.vendor % ?)
                  AND (? IS NULL OR jsonb_exists(r.metadata->'categories', ?))
                  AND (? IS NULL OR (marketplace_semver_compare(?,r.manifest->>'minimumCoreVersion')>=0 AND marketplace_semver_compare(?,r.manifest->>'maximumCoreVersion')<=0))
                  AND (? IS NULL OR r.manifest->'geographicAvailability'->>'scope'='GLOBAL' OR jsonb_exists(r.manifest->'geographicAvailability'->'countries',?))
                """;
    List<Object> filterArgs =
        new ArrayList<>(
            java.util.Arrays.asList(
                term,
                term,
                term,
                term,
                category,
                category,
                coreVersion,
                coreVersion,
                coreVersion,
                country,
                country));
    var rows =
        jdbc.query(
            """
                SELECT p.plugin_id,p.slug,p.vendor,r.version,r.manifest_document AS manifest,r.metadata::text,r.published_at
                FROM marketplace_plugins p
                JOIN LATERAL (SELECT version,manifest,metadata,published_at FROM marketplace_releases
                  WHERE plugin_id=p.plugin_id AND publication_state='PUBLISHED' ORDER BY published_at DESC LIMIT 1) r ON true
                WHERE p.listing_state='LISTED'
                """
                + where
                + """
                ORDER BY CASE WHEN ? IS NULL THEN 0 ELSE greatest(similarity(p.plugin_id,?),similarity(p.slug,?),similarity(p.vendor,?)) END DESC,p.plugin_id
                LIMIT ? OFFSET ?
                """,
            (rs, n) ->
                releaseCard(
                    rs.getString("plugin_id"),
                    rs.getString("slug"),
                    rs.getString("vendor"),
                    rs.getString("version"),
                    rs.getString("manifest"),
                    rs.getString("metadata"),
                    rs.getObject("published_at", java.time.OffsetDateTime.class),
                    locale),
            java.util.stream.Stream.concat(
                    filterArgs.stream(),
                    java.util.stream.Stream.of(term, term, term, term, size, page * size))
                .toArray());
    Long total =
        jdbc.queryForObject(
            """
                SELECT count(*) FROM marketplace_plugins p
                JOIN LATERAL (SELECT version,manifest,metadata,published_at FROM marketplace_releases WHERE plugin_id=p.plugin_id AND publication_state='PUBLISHED' ORDER BY published_at DESC LIMIT 1) r ON true
                WHERE p.listing_state='LISTED'
                """
                + where,
            Long.class,
            filterArgs.toArray());
    return new Page(rows, page, size, total == null ? 0 : total);
  }

  @Override
  public Map<String, Object> plugin(String pluginId, String locale) {
    validateLocale(locale);
    var list =
        jdbc.query(
            "SELECT p.plugin_id,p.slug,p.vendor,r.version,r.manifest_document AS manifest,r.metadata::text,r.published_at FROM marketplace_plugins p JOIN LATERAL (SELECT version,manifest_document,metadata,published_at FROM marketplace_releases WHERE plugin_id=p.plugin_id AND publication_state='PUBLISHED' ORDER BY published_at DESC LIMIT 1) r ON true WHERE p.plugin_id=? AND p.listing_state='LISTED'",
            (rs, n) ->
                releaseCard(
                    rs.getString("plugin_id"),
                    rs.getString("slug"),
                    rs.getString("vendor"),
                    rs.getString("version"),
                    rs.getString("manifest"),
                    rs.getString("metadata"),
                    rs.getObject("published_at", java.time.OffsetDateTime.class),
                    locale),
            pluginId);
    if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found");
    var response = new LinkedHashMap<>(list.getFirst());
    response.put("releases", releases(pluginId, locale, 0, 20));
    return response;
  }

  @Override
  public Page releases(String pluginId, String locale, int page, int size) {
    checkPaging(page, size);
    validateLocale(locale);
    requireListed(pluginId);
    var rows =
        jdbc.query(
            "SELECT r.version,r.metadata::text,r.published_at FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id WHERE r.plugin_id=? AND r.publication_state='PUBLISHED' AND p.listing_state='LISTED' ORDER BY r.published_at DESC,r.version LIMIT ? OFFSET ?",
            (rs, n) ->
                Map.<String, Object>of(
                    "version",
                    rs.getString(1),
                    "publishedAt",
                    rs.getObject(3, java.time.OffsetDateTime.class),
                    "releaseNotes",
                    localized(json(rs.getString(2)), locale)
                        .getOrDefault("releaseNotes", Map.of())),
            pluginId,
            size,
            page * size);
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id WHERE r.plugin_id=? AND r.publication_state='PUBLISHED' AND p.listing_state='LISTED'",
            Long.class,
            pluginId);
    return new Page(rows, page, size, total == null ? 0 : total);
  }

  @Override
  public Map<String, Object> release(String pluginId, String version, String locale) {
    validateLocale(locale);
    SemanticVersion.parse(version);
    var list =
        jdbc.query(
            "SELECT r.manifest_document AS manifest,r.metadata::text,r.published_at,p.slug,p.vendor FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id WHERE r.plugin_id=? AND r.version=? AND r.publication_state='PUBLISHED' AND p.listing_state='LISTED'",
            (rs, n) ->
                releaseCard(
                    pluginId,
                    rs.getString("slug"),
                    rs.getString("vendor"),
                    version,
                    rs.getString("manifest"),
                    rs.getString("metadata"),
                    rs.getObject("published_at", java.time.OffsetDateTime.class),
                    locale),
            pluginId,
            version);
    if (list.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Release not found");
    return list.getFirst();
  }

  @Override
  public Map<String, Object> latestCompatible(
      String pluginId, String coreVersion, String country, boolean includePrerelease) {
    var core = SemanticVersion.parse(coreVersion);
    validateCountry(country);
    var all =
        jdbc.query(
            "SELECT version,manifest_document AS manifest,metadata::text,published_at,p.slug,p.vendor FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id WHERE r.plugin_id=? AND r.publication_state='PUBLISHED' AND p.listing_state='LISTED'",
            (rs, n) ->
                releaseCard(
                    pluginId,
                    rs.getString("slug"),
                    rs.getString("vendor"),
                    rs.getString("version"),
                    rs.getString("manifest"),
                    rs.getString("metadata"),
                    rs.getObject("published_at", java.time.OffsetDateTime.class),
                    "en"),
            pluginId);
    return all.stream()
        .filter(
            r -> {
              var version = SemanticVersion.parse((String) r.get("version"));
              if (version.isPrerelease() && !includePrerelease) return false;
              Map<?, ?> m = (Map<?, ?>) r.get("manifest");
              return core.compareTo(SemanticVersion.parse((String) m.get("minimumCoreVersion")))
                      >= 0
                  && core.compareTo(SemanticVersion.parse((String) m.get("maximumCoreVersion")))
                      <= 0
                  && (country == null
                      || countryMatches((Map<?, ?>) m.get("geographicAvailability"), country));
            })
        .max(
            (a, b) ->
                SemanticVersion.parse((String) a.get("version"))
                    .compareTo(SemanticVersion.parse((String) b.get("version"))))
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No compatible release found"));
  }

  private Map<String, Object> releaseCard(
      String id,
      String slug,
      String vendor,
      String version,
      String manifestJson,
      String metadataJson,
      java.time.OffsetDateTime date,
      String locale) {
    Map<String, Object> manifest = json(manifestJson), meta = json(metadataJson);
    var card = new LinkedHashMap<String, Object>();
    card.put("pluginId", id);
    card.put("slug", slug);
    card.put("version", version);
    card.put("vendor", vendor);
    var publicMeta = new LinkedHashMap<>(localized(meta, locale));
    Object notes = publicMeta.remove("releaseNotes");
    card.put("metadata", publicMeta);
    card.put("releaseNotes", notes);
    card.put("manifest", manifest);
    card.put("publishedAt", date);
    card.put(
        "compatibility",
        Map.of(
            "minimumCoreVersion",
            manifest.get("minimumCoreVersion"),
            "maximumCoreVersion",
            manifest.get("maximumCoreVersion"),
            "installTimeChecksPending",
            true,
            "requiredDependencies",
            manifest.getOrDefault("dependencies", List.of())));
    return card;
  }

  private Map<String, Object> localized(Map<String, Object> m, String locale) {
    locale = locale.toLowerCase(java.util.Locale.ROOT);
    var out = new LinkedHashMap<>(m);
    for (String key : List.of("title", "tagline", "description", "highlights", "releaseNotes"))
      if (m.get(key) instanceof Map<?, ?> values)
        out.put(key, values.containsKey(locale) ? values.get(locale) : values.get("en"));
    return out;
  }

  private Map<String, Object> json(String text) {
    try {
      return mapper.readValue(text, MAP);
    } catch (Exception e) {
      throw new IllegalStateException("Stored catalog JSON is invalid", e);
    }
  }

  private void requireListed(String pluginId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketplace_plugins WHERE plugin_id=? AND listing_state='LISTED' AND EXISTS(SELECT 1 FROM marketplace_releases WHERE plugin_id=? AND publication_state='PUBLISHED')",
            Integer.class,
            pluginId,
            pluginId);
    if (n == null || n == 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found");
  }

  private static void validateLocale(String locale) {
    if (locale != null
        && !Set.of("en", "de", "fr", "it").contains(locale.toLowerCase(java.util.Locale.ROOT)))
      throw new IllegalArgumentException("locale must be en, de, fr or it");
  }

  private static void validateCountry(String country) {
    if (country != null
        && (!country.matches("[A-Z]{2}")
            || !Set.of(java.util.Locale.getISOCountries()).contains(country)))
      throw new IllegalArgumentException("country must be an ISO 3166-1 alpha-2 code");
  }

  private static boolean countryMatches(Map<?, ?> availability, String country) {
    if (availability == null) return false;
    if ("GLOBAL".equals(availability.get("scope"))) return true;
    Object countries = availability.get("countries");
    return countries instanceof List<?> values && values.contains(country);
  }

  private static void checkPaging(int page, int size) {
    if (page < 0 || size < 1 || size > 100 || (long) page * size > 100_000_000L)
      throw new IllegalArgumentException(
          "page must be non-negative and size must be between 1 and 100");
  }
}
