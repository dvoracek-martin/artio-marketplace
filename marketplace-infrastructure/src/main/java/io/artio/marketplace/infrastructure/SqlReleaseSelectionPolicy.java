package io.artio.marketplace.infrastructure;

/** Shared SQL predicate and ordering for every public install recommendation surface. */
final class SqlReleaseSelectionPolicy {
  private SqlReleaseSelectionPolicy() {}

  static String eligible(String release, String plugin) {
    return """
        %s.moderation_state='ACTIVE'
        AND %s.publication_state='PUBLISHED'
        AND (CAST(? AS boolean) OR %s.version !~ '^[0-9]+\\.[0-9]+\\.[0-9]+-')
        AND (CAST(? AS text) IS NULL OR (marketplace_semver_compare(CAST(? AS text),%s.manifest->>'minimumCoreVersion')>=0
          AND marketplace_semver_compare(CAST(? AS text),%s.manifest->>'maximumCoreVersion')<=0))
        AND (CAST(? AS text) IS NULL OR %s.manifest->'geographicAvailability'->>'scope'='GLOBAL'
          OR jsonb_exists(%s.manifest->'geographicAvailability'->'countries',CAST(? AS text)))
        """
        .formatted(plugin, release, release, release, release, release, release);
  }

  static String orderBy(String release) {
    return "marketplace_semver_sort_key("
        + release
        + ".version) COLLATE \"C\" DESC, "
        + release
        + ".version COLLATE \"C\" DESC";
  }

  static Object[] arguments(String coreVersion, String country, boolean includePrerelease) {
    return new Object[] {
      includePrerelease, coreVersion, coreVersion, coreVersion, country, country
    };
  }
}
