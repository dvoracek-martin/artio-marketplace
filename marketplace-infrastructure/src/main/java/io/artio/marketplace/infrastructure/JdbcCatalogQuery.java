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

/**
 * JDBC adapter; every recommendation surface uses the same release eligibility and SemVer order.
 */
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
      boolean includePrerelease,
      String locale,
      int page,
      int size) {
    checkPaging(page, size);
    validate(locale, coreVersion, country);
    String term = query == null || query.isBlank() ? null : query.trim();
    if (term != null && term.length() > 100)
      throw new IllegalArgumentException("q must be at most 100 characters");
    String lateral = latestLateral();
    String filters =
        " AND (CAST(? AS text) IS NULL OR jsonb_exists(r.metadata->'categories', CAST(? AS text)))";
    String from =
        " FROM marketplace_plugins p JOIN LATERAL (" + lateral + ") r ON true WHERE 1=1" + filters;
    var args = new ArrayList<Object>();
    addEligibilityArgs(args, coreVersion, country, includePrerelease);
    args.add(category);
    args.add(category);
    args.add(term);
    args.add(term);
    args.add(term);
    args.add(term);
    args.add(term);
    args.add(term);
    args.add(term);
    args.add(term);
    args.add(size);
    args.add(page * size);
    var rows =
        jdbc.query(
            "SELECT p.plugin_id,p.slug,p.vendor,r.version,r.manifest_document AS manifest,r.metadata::text,r.published_at"
                + from
                + " AND (CAST(? AS text) IS NULL OR p.plugin_id % CAST(? AS text) OR p.slug % CAST(? AS text) OR p.vendor % CAST(? AS text))"
                + " ORDER BY CASE WHEN CAST(? AS text) IS NULL THEN 0 ELSE greatest(similarity(p.plugin_id,CAST(? AS text)),similarity(p.slug,CAST(? AS text)),similarity(p.vendor,CAST(? AS text))) END DESC,p.plugin_id"
                + " LIMIT ? OFFSET ?",
            (rs, n) -> card(rs, locale),
            args.toArray());

    // The count uses exactly the same eligible release and listing filters as this page.
    List<Object> countArgs = new ArrayList<>();
    addEligibilityArgs(countArgs, coreVersion, country, includePrerelease);
    countArgs.add(category);
    countArgs.add(category);
    countArgs.add(term);
    countArgs.add(term);
    countArgs.add(term);
    countArgs.add(term);
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketplace_plugins p JOIN LATERAL ("
                + lateral
                + ") r ON true WHERE 1=1"
                + filters
                + " AND (CAST(? AS text) IS NULL OR p.plugin_id % CAST(? AS text) OR p.slug % CAST(? AS text) OR p.vendor % CAST(? AS text))",
            Long.class,
            countArgs.toArray());
    return new Page(rows, page, size, total == null ? 0 : total);
  }

  @Override
  public Map<String, Object> plugin(
      String pluginId,
      String coreVersion,
      String country,
      boolean includePrerelease,
      String locale) {
    validate(locale, coreVersion, country);
    var rows =
        jdbc.query(
            "SELECT p.plugin_id,p.slug,p.vendor,r.version,r.manifest_document AS manifest,r.metadata::text,r.published_at"
                + " FROM marketplace_plugins p JOIN LATERAL ("
                + latestLateral()
                + ") r ON true WHERE p.plugin_id=?",
            (rs, n) -> card(rs, locale),
            eligibilityFirst(coreVersion, country, includePrerelease, pluginId));
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found");
    var response = new LinkedHashMap<>(rows.getFirst());
    response.put(
        "releases", releases(pluginId, coreVersion, country, includePrerelease, locale, 0, 20));
    return response;
  }

  @Override
  public Page releases(
      String pluginId,
      String coreVersion,
      String country,
      boolean includePrerelease,
      String locale,
      int page,
      int size) {
    checkPaging(page, size);
    validate(locale, coreVersion, country);
    String filter = " WHERE r.plugin_id=? AND " + SqlReleaseSelectionPolicy.eligible("r", "p");
    List<Object> args = new ArrayList<>();
    args.add(pluginId);
    addEligibilityArgs(args, coreVersion, country, includePrerelease);
    args.add(size);
    args.add(page * size);
    var rows =
        jdbc.query(
            "SELECT r.version,r.metadata::text,r.published_at FROM marketplace_releases r"
                + " JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id"
                + filter
                + " ORDER BY "
                + SqlReleaseSelectionPolicy.orderBy("r")
                + " LIMIT ? OFFSET ?",
            (rs, n) ->
                Map.<String, Object>of(
                    "version", rs.getString(1),
                    "publishedAt", rs.getObject(3, java.time.OffsetDateTime.class),
                    "releaseNotes",
                        localized(json(rs.getString(2)), locale)
                            .getOrDefault("releaseNotes", Map.of())),
            args.toArray());
    List<Object> countArgs = new ArrayList<>();
    countArgs.add(pluginId);
    addEligibilityArgs(countArgs, coreVersion, country, includePrerelease);
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id"
                + filter,
            Long.class,
            countArgs.toArray());
    return new Page(rows, page, size, total == null ? 0 : total);
  }

  @Override
  public Map<String, Object> release(
      String pluginId,
      String version,
      String coreVersion,
      String country,
      boolean includePrerelease,
      String locale) {
    validate(locale, coreVersion, country);
    SemanticVersion.parse(version);
    var rows =
        jdbc.query(
            "SELECT r.manifest_document AS manifest,r.metadata::text,r.published_at,p.slug,p.vendor"
                + " FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id"
                + " WHERE r.plugin_id=? AND r.version=? AND "
                + SqlReleaseSelectionPolicy.eligible("r", "p"),
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
            eligibilityThen(coreVersion, country, includePrerelease, List.of(pluginId, version)));
    if (rows.isEmpty()) rejectUnavailableRelease(pluginId, version);
    return rows.getFirst();
  }

  @Override
  public Map<String, Object> latestCompatible(
      String pluginId, String coreVersion, String country, boolean includePrerelease) {
    validate("en", coreVersion, country);
    var rows =
        jdbc.query(
            "SELECT r.version,r.manifest_document AS manifest,r.metadata::text,r.published_at,p.slug,p.vendor"
                + " FROM marketplace_releases r JOIN marketplace_plugins p ON p.plugin_id=r.plugin_id"
                + " WHERE r.plugin_id=? AND "
                + SqlReleaseSelectionPolicy.eligible("r", "p")
                + " ORDER BY "
                + SqlReleaseSelectionPolicy.orderBy("r")
                + " LIMIT 1",
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
            eligibilityThen(coreVersion, country, includePrerelease, List.of(pluginId)));
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No compatible release found");
    return rows.getFirst();
  }

  private String latestLateral() {
    return "SELECT r.version,r.manifest,r.manifest_document,r.metadata,r.published_at"
        + " FROM marketplace_releases r WHERE r.plugin_id=p.plugin_id AND "
        + SqlReleaseSelectionPolicy.eligible("r", "p")
        + " ORDER BY "
        + SqlReleaseSelectionPolicy.orderBy("r")
        + " LIMIT 1";
  }

  private void rejectUnavailableRelease(String pluginId, String version) {
    var state =
        jdbc.query(
            "SELECT p.moderation_state,r.publication_state FROM marketplace_plugins p"
                + " LEFT JOIN marketplace_releases r ON r.plugin_id=p.plugin_id AND r.version=?"
                + " WHERE p.plugin_id=?",
            (rs, n) -> List.of(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2)),
            version,
            pluginId);
    if (!state.isEmpty()
        && "ACTIVE".equals(state.getFirst().getFirst())
        && "REVOKED".equals(state.getFirst().getLast()))
      throw new ResponseStatusException(
          HttpStatus.GONE, "Release is revoked and is not installable");
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Release not found or not eligible");
  }

  private Map<String, Object> card(java.sql.ResultSet rs, String locale)
      throws java.sql.SQLException {
    return releaseCard(
        rs.getString("plugin_id"),
        rs.getString("slug"),
        rs.getString("vendor"),
        rs.getString("version"),
        rs.getString("manifest"),
        rs.getString("metadata"),
        rs.getObject("published_at", java.time.OffsetDateTime.class),
        locale);
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
    Map<String, Object> packageManifest = stringMap(manifest.get("packageManifest"), manifest);
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
            "minimumCoreVersion", packageManifest.get("minimumCoreVersion"),
            "maximumCoreVersion", packageManifest.get("maximumCoreVersion"),
            "installTimeChecksPending", true,
            "requiredDependencies", packageManifest.getOrDefault("dependencies", List.of())));
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

  private Map<String, Object> stringMap(Object value, Map<String, Object> fallback) {
    if (!(value instanceof Map<?, ?> nested)) return fallback;
    var result = new LinkedHashMap<String, Object>();
    nested.forEach((key, nestedValue) -> result.put(String.valueOf(key), nestedValue));
    return result;
  }

  private void validate(String locale, String coreVersion, String country) {
    validateLocale(locale);
    if (coreVersion != null) SemanticVersion.parse(coreVersion);
    validateCountry(country);
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

  private static void checkPaging(int page, int size) {
    if (page < 0 || size < 1 || size > 100 || (long) page * size > 100_000_000L)
      throw new IllegalArgumentException(
          "page must be non-negative and size must be between 1 and 100");
  }

  private static void addEligibilityArgs(
      List<Object> args, String coreVersion, String country, boolean includePrerelease) {
    args.add(includePrerelease);
    args.add(coreVersion);
    args.add(coreVersion);
    args.add(coreVersion);
    args.add(country);
    args.add(country);
  }

  private static Object[] eligibilityThen(
      String coreVersion, String country, boolean includePrerelease, List<?> trailing) {
    var args = new ArrayList<Object>();
    args.addAll(trailing);
    addEligibilityArgs(args, coreVersion, country, includePrerelease);
    return args.toArray();
  }

  private static Object[] eligibilityFirst(
      String coreVersion, String country, boolean includePrerelease, String trailing) {
    var args = new ArrayList<Object>();
    addEligibilityArgs(args, coreVersion, country, includePrerelease);
    args.add(trailing);
    return args.toArray();
  }

  private static List<Object> concat(List<?> first, List<?> second) {
    var args = new ArrayList<Object>(first.size() + second.size());
    args.addAll(first);
    args.addAll(second);
    return args;
  }
}
