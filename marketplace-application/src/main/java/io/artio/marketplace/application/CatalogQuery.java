package io.artio.marketplace.application;

import java.util.List;
import java.util.Map;

/** Input port for the read-only public catalog. */
public interface CatalogQuery {
  Page plugins(
      String query,
      String category,
      String country,
      String coreVersion,
      String locale,
      int page,
      int size);

  Map<String, Object> plugin(String pluginId, String locale);

  Page releases(String pluginId, String locale, int page, int size);

  Map<String, Object> release(String pluginId, String version, String locale);

  Map<String, Object> latestCompatible(
      String pluginId, String coreVersion, String country, boolean includePrerelease);

  record Page(List<Map<String, Object>> content, int page, int size, long totalElements) {}
}
