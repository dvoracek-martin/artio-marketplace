package io.artio.extensions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Runtime dependency rules shared by installation and activation workflows. */
public final class PluginDependencyPolicy {
  private PluginDependencyPolicy() {}

  public static List<String> validate(
      PluginManifest manifest, Map<String, InstalledPlugin> installed, boolean requireEnabled) {
    List<String> errors = new ArrayList<>();
    for (PluginManifest.Dependency dependency : manifest.dependencies()) {
      InstalledPlugin actual = installed.get(dependency.id());
      if (actual == null) {
        errors.add("dependency is not installed: " + dependency.id());
        continue;
      }
      if (requireEnabled && !"ENABLED".equals(actual.lifecycleState())) {
        errors.add("dependency is not enabled: " + dependency.id());
      } else if (!requireEnabled
          && !List.of("INSTALLED", "ENABLED", "DISABLED").contains(actual.lifecycleState())) {
        errors.add("dependency is not installed: " + dependency.id());
      }
      if (compare(actual.version(), dependency.minimumVersion()) < 0
          || compare(actual.version(), dependency.maximumVersion()) > 0) {
        errors.add("dependency version is outside the declared range: " + dependency.id());
      }
    }
    return List.copyOf(errors);
  }

  public static List<String> enabledDependents(
      String pluginId, Collection<PluginManifest> enabled) {
    return enabled.stream()
        .filter(plugin -> plugin.dependencies().stream().anyMatch(dep -> dep.id().equals(pluginId)))
        .map(PluginManifest::id)
        .sorted()
        .toList();
  }

  private static int compare(String left, String right) {
    String[] leftParts = left.split("-", 2);
    String[] rightParts = right.split("-", 2);
    String[] l = leftParts[0].split("\\."), r = rightParts[0].split("\\.");
    for (int i = 0; i < 3; i++) {
      int order = Integer.compare(Integer.parseInt(l[i]), Integer.parseInt(r[i]));
      if (order != 0) return order;
    }
    if (leftParts.length == 1 && rightParts.length == 1) return 0;
    if (leftParts.length == 1) return 1;
    if (rightParts.length == 1) return -1;
    String[] leftIdentifiers = leftParts[1].split("\\."),
        rightIdentifiers = rightParts[1].split("\\.");
    int common = Math.min(leftIdentifiers.length, rightIdentifiers.length);
    for (int i = 0; i < common; i++) {
      String li = leftIdentifiers[i], ri = rightIdentifiers[i];
      boolean ln = li.matches("0|[1-9][0-9]*"), rn = ri.matches("0|[1-9][0-9]*");
      int order;
      if (ln && rn) order = new java.math.BigInteger(li).compareTo(new java.math.BigInteger(ri));
      else if (ln != rn) order = ln ? -1 : 1;
      else order = li.compareTo(ri);
      if (order != 0) return order;
    }
    return Integer.compare(leftIdentifiers.length, rightIdentifiers.length);
  }

  public record InstalledPlugin(String version, String lifecycleState) {}
}
