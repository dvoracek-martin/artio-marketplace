package io.artio.extensions;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies Core-owned allowlists and structural limits to untrusted plugin metadata. */
public final class PluginManifestValidator {
  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");
  private static final Pattern DIGEST_IMAGE =
      Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:/-]*@sha256:[a-f0-9]{64}");
  private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");
  private static final Set<String> PERMISSIONS =
      Set.of(
          "payment.process",
          "shipping.quote",
          "shipping.label",
          "catalog.read",
          "orders.read",
          "orders.write",
          "notifications.send");
  private static final Set<String> FIELD_TYPES =
      Set.of("text", "secret", "boolean", "enum", "number", "url", "json");
  private static final Set<String> EXTENSION_POINTS =
      Set.of(
          "payment.provider.v1",
          "shipping.provider.v1",
          "catalog.enrichment.v1",
          "catalog.search.v1",
          "notification.channel.v1");
  private final Set<String> trustedRegistries;

  public PluginManifestValidator(Set<String> trustedRegistries) {
    this.trustedRegistries =
        trustedRegistries.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public List<String> validate(
      PluginManifest manifest, String coreVersion, Set<String> installedPluginIds) {
    return validate(manifest, coreVersion, installedPluginIds, false);
  }

  private List<String> validate(
      PluginManifest manifest, String coreVersion, Set<String> installedPluginIds,
      boolean allowV2Properties) {
    List<String> errors = new ArrayList<>();
    if (!(allowV2Properties ? "2" : "1").equals(manifest.manifestVersion()))
      errors.add(allowV2Properties ? "manifestVersion must be 2" : "manifestVersion must be 1");
    if (!ID.matcher(manifest.id()).matches())
      errors.add("id must be a lowercase reverse-DNS style identifier");
    if (manifest.name().isBlank() || manifest.name().length() > 120)
      errors.add("name must contain 1 to 120 characters");
    if (manifest.vendor().isBlank() || manifest.vendor().length() > 120)
      errors.add("vendor must contain 1 to 120 characters");
    if (!isSemver(manifest.version())) errors.add("version must use semantic versioning");
    if (manifest.description().length() > 2000) errors.add("description exceeds 2000 characters");
    if (!isSemver(manifest.minimumCoreVersion()) || !isSemver(manifest.maximumCoreVersion()))
      errors.add("Core compatibility bounds must use semantic versions");
    else if (compare(coreVersion, manifest.minimumCoreVersion()) < 0
        || compare(coreVersion, manifest.maximumCoreVersion()) > 0)
      errors.add("plugin is incompatible with this Core version");
    validateImage(manifest.image(), "image", errors);
    validateGeographicAvailability(manifest.geographicAvailability(), errors);
    Set<String> permissions = new HashSet<>();
    for (String permission : manifest.permissions()) {
      if (permission == null || !PERMISSIONS.contains(permission))
        errors.add("unsupported permission: " + permission);
      if (!permissions.add(permission)) errors.add("duplicate permission: " + permission);
    }
    Set<String> points = new HashSet<>();
    for (String point : manifest.extensionPoints()) {
      if (point == null || !EXTENSION_POINTS.contains(point))
        errors.add("unsupported extension point: " + point);
      if (!points.add(point)) errors.add("duplicate extension point: " + point);
    }
    Set<String> dependencies = new HashSet<>();
    for (var dependency : manifest.dependencies()) {
      if (dependency == null) {
        errors.add("dependency cannot be null");
        continue;
      }
      String dependencyId = dependency.id();
      boolean validId = dependencyId != null && ID.matcher(dependencyId).matches();
      if (!validId) errors.add("invalid dependency id: " + dependencyId);
      if (!dependencies.add(dependencyId)) errors.add("duplicate dependency: " + dependencyId);
      if (manifest.id().equals(dependencyId)) errors.add("plugin cannot depend on itself");
      if (validId && !installedPluginIds.contains(dependencyId))
        errors.add("missing dependency: " + dependencyId);
      if (!isSemver(dependency.minimumVersion()) || !isSemver(dependency.maximumVersion())) {
        errors.add("invalid dependency version range: " + dependencyId);
      } else if (compare(dependency.minimumVersion(), dependency.maximumVersion()) > 0) {
        errors.add("inverted dependency version range: " + dependencyId);
      }
    }
    if (manifest.configuration().size() > 100)
      errors.add("configuration schema exceeds 100 fields");
    manifest.configuration().forEach((key, field) -> validateField(key, field, errors));
    if (!allowV2Properties && manifest.configuration().values().stream()
        .anyMatch(field -> field != null && (field.generated() || field.generatedLength() != null
            || field.generatedRotationGroup() != null)))
      errors.add("generated configuration secrets require manifest v2");
    validateConfigurationBindings(
        manifest.configurationBindings(), manifest.configuration(), "primary service", errors);
    validatePath(manifest.healthPath(), "healthPath", errors);
    validatePath(manifest.diagnosticsPath(), "diagnosticsPath", errors);
    validatePersistentPaths(manifest.persistentPaths(), "primary plugin", errors);
    if (manifest.services().size() > 8)
      errors.add("plugin bundle may define at most 8 sidecar services");
    Set<String> serviceIds = new HashSet<>();
    for (var service : manifest.services()) {
      if (service == null) {
        errors.add("bundle service cannot be null");
        continue;
      }
      if (service.id() == null || !service.id().matches("[a-z][a-z0-9-]{0,30}"))
        errors.add("bundle service id must be a lowercase service slug");
      else {
        if (service.id().equals("main"))
          errors.add("bundle service id 'main' is reserved for the primary plugin image");
        if (!serviceIds.add(service.id()))
          errors.add("duplicate bundle service id: " + service.id());
      }
      validateImage(service.image(), "bundle service image", errors);
      if (!allowV2Properties && (service.beforePrimary() || service.runToCompletion()))
        errors.add("bundle service lifecycle options require manifest v2");
      if (service.runToCompletion()) {
        if (service.port() != 0 || service.healthPath() != null
            || !service.persistentPaths().isEmpty() || !service.internalPorts().isEmpty())
          errors.add("one-shot bundle services must not declare ports, health probes or persistent volumes");
      } else {
        if (service.port() < 1 || service.port() > 65535)
          errors.add("bundle service port must be between 1 and 65535");
        validatePath(service.healthPath(), "bundle service healthPath", errors);
      }
      if (service.memoryLimitMb() != null
          && (service.memoryLimitMb() < 128 || service.memoryLimitMb() > 2048)) {
        errors.add("bundle service memoryLimitMb must be between 128 and 2048");
      }
      validateConfigurationBindings(
          service.configurationBindings(),
          manifest.configuration(),
          "bundle service " + service.id(),
          errors);
      validatePersistentPaths(service.persistentPaths(), "bundle service " + service.id(), errors);
      if (service.internalPorts().size() > 8)
        errors.add("bundle service may declare at most 8 additional private ports");
      Set<Integer> ports = new HashSet<>();
      for (Integer additionalPort : service.internalPorts()) {
        if (additionalPort == null || additionalPort < 1 || additionalPort > 65535) {
          errors.add("bundle additional private port must be between 1 and 65535");
        } else if (additionalPort == service.port() || !ports.add(additionalPort)) {
          errors.add(
              "bundle additional private ports must be unique and differ from the health port");
        }
      }
    }
    Set<String> knownServiceIds = new HashSet<>(serviceIds);
    knownServiceIds.add("main");
    for (var service : manifest.services()) {
      if (service == null || service.id() == null) continue;
      Set<String> startupDependencies = new HashSet<>();
      for (String dependency : service.dependsOn()) {
        if (dependency == null || !knownServiceIds.contains(dependency)) {
          errors.add("bundle service " + service.id() + " depends on an unknown service");
        } else if (dependency.equals(service.id())) {
          errors.add("bundle service cannot depend on itself: " + service.id());
        } else if (!startupDependencies.add(dependency)) {
          errors.add("bundle service has a duplicate startup dependency: " + service.id());
        }
      }
    }
    if (hasServiceDependencyCycle(manifest.services()))
      errors.add("bundle service startup dependencies contain a cycle");
    if (hasPrimaryDependencyCycle(manifest.services()))
      errors.add("bundle service dependencies on the primary contain a cycle");
    return List.copyOf(errors);
  }

  /** Validates the v2 capability envelope while reusing the unchanged v1 package safety rules. */
  public List<String> validate(
      PluginManifestV2 manifest, String coreVersion, Set<String> installedPluginIds) {
    if (manifest == null) return List.of("manifest is required");
    PluginManifest source = manifest.packageManifest();
    if (source == null) return List.of("v2 package manifest is required");
    PluginManifest packageMetadata =
        new PluginManifest(
            "2",
            source.id(),
            source.name(),
            source.vendor(),
            source.version(),
            source.description(),
            source.minimumCoreVersion(),
            source.maximumCoreVersion(),
            source.image(),
            source.dependencies(),
            List.of(),
            source.configuration(),
            List.of(),
            source.healthPath(),
            source.diagnosticsPath(),
            source.geographicAvailability(),
            source.services(),
            source.persistentPaths(),
            source.configurationBindings());
    List<String> errors =
        new ArrayList<>(validate(packageMetadata, coreVersion, installedPluginIds, true));
    source.configuration().forEach((key, field) -> {
      if (field != null && field.generated() && (!field.secret() || !"secret".equals(field.type())
          || !field.required() || field.generatedLength() == null
          || field.generatedLength() < 16 || field.generatedLength() > 64))
        errors.add("generated configuration secrets must be required secrets with a generated length from 16 to 64: " + key);
      if (field != null && !field.generated() && field.generatedLength() != null)
        errors.add("generatedLength requires a generated secret field: " + key);
      if (field != null && field.generatedRotationGroup() != null
          && (!field.generated() || !field.secret()
              || !field.generatedRotationGroup().matches("[a-z][a-z0-9._-]{0,31}")))
        errors.add("generatedRotationGroup requires a generated secret and a valid group name: " + key);
    });
    if (!source.extensionPoints().isEmpty())
      errors.add("v2 manifests must declare capabilities instead of legacy extensionPoints");
    if (!source.permissions().isEmpty())
      errors.add("v2 manifests must declare permissions on capability operations");
    errors.addAll(new PluginManifestV2Validator().validate(manifest));
    return List.copyOf(errors);
  }

  private static void validatePersistentPaths(
      List<String> paths, String owner, List<String> errors) {
    if (paths.size() > 4) errors.add(owner + " may declare at most 4 persistent paths");
    Set<String> unique = new HashSet<>();
    for (String path : paths) {
      if (path == null
          || !(path.matches("/data(?:/[A-Za-z0-9._-]+)+")
              || path.matches("/var/lib(?:/[A-Za-z0-9._-]+)+")
              || path.equals("/prometheus"))
          || path.contains(".."))
        errors.add(
            owner.startsWith("bundle service")
                ? "bundle persistent path must be under /data, /var/lib, or equal /prometheus"
                : "primary plugin persistent path must be under /data, /var/lib, or equal /prometheus");
      else {
        if (!unique.add(path)) errors.add(owner + " has a duplicate persistent path");
        if (unique.stream()
            .anyMatch(
                existing ->
                    !existing.equals(path)
                        && (existing.startsWith(path + "/") || path.startsWith(existing + "/")))) {
          errors.add(
              owner.startsWith("bundle service")
                  ? "bundle persistent paths must not overlap"
                  : "primary plugin persistent paths must not overlap");
        }
      }
    }
  }

  private static boolean hasServiceDependencyCycle(List<PluginManifest.BundleService> services) {
    java.util.Map<String, PluginManifest.BundleService> byId = new java.util.HashMap<>();
    for (var service : services)
      if (service != null && service.id() != null) byId.putIfAbsent(service.id(), service);
    Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
    for (String id : byId.keySet())
      if (hasServiceDependencyCycle(id, byId, visiting, visited)) return true;
    return false;
  }

  private static boolean hasPrimaryDependencyCycle(List<PluginManifest.BundleService> services) {
    var nonNullServices = services.stream().filter(java.util.Objects::nonNull).toList();
    var dependencies = new java.util.HashMap<String, Set<String>>();
    dependencies.put("main", nonNullServices.stream().filter(PluginManifest.BundleService::beforePrimary)
        .map(PluginManifest.BundleService::id).collect(java.util.stream.Collectors.toSet()));
    nonNullServices.forEach(service -> dependencies.put(service.id(), new HashSet<>(service.dependsOn())));
    return dependencyCycle("main", dependencies, new HashSet<>(), new HashSet<>());
  }

  private static boolean dependencyCycle(String id, java.util.Map<String, Set<String>> dependencies,
      Set<String> visited, Set<String> visiting) {
    if (visited.contains(id)) return false;
    if (!visiting.add(id)) return true;
    for (String dependency : dependencies.getOrDefault(id, Set.of())) {
      if (dependencies.containsKey(dependency)
          && dependencyCycle(dependency, dependencies, visited, visiting)) return true;
    }
    visiting.remove(id);
    visited.add(id);
    return false;
  }

  private static boolean hasServiceDependencyCycle(
      String id,
      java.util.Map<String, PluginManifest.BundleService> services,
      Set<String> visiting,
      Set<String> visited) {
    if (visiting.contains(id)) return true;
    if (!visited.add(id)) return false;
    visiting.add(id);
    for (String dependency : services.get(id).dependsOn()) {
      if (services.containsKey(dependency)
          && hasServiceDependencyCycle(dependency, services, visiting, visited)) return true;
    }
    visiting.remove(id);
    return false;
  }

  private static void validateGeographicAvailability(
      PluginManifest.GeographicAvailability availability, List<String> errors) {
    if (availability == null) {
      errors.add("geographicAvailability is required");
      return;
    }
    if (availability.scope() == null
        || !Set.of("GLOBAL", "COUNTRY_SPECIFIC").contains(availability.scope())) {
      errors.add("geographicAvailability.scope must be GLOBAL or COUNTRY_SPECIFIC");
      return;
    }
    Set<String> countries = new HashSet<>();
    Set<String> isoCountries = Set.of(Locale.getISOCountries());
    for (String country : availability.countries()) {
      if (country == null || !country.matches("[A-Z]{2}") || !isoCountries.contains(country)) {
        errors.add("geographicAvailability countries must use ISO 3166-1 alpha-2 codes");
      }
      if (country != null && !countries.add(country))
        errors.add("geographicAvailability contains duplicate country: " + country);
    }
    if ("GLOBAL".equals(availability.scope()) && !availability.countries().isEmpty()) {
      errors.add("GLOBAL geographicAvailability must not list countries");
    }
    if ("COUNTRY_SPECIFIC".equals(availability.scope()) && availability.countries().isEmpty()) {
      errors.add("COUNTRY_SPECIFIC geographicAvailability must list at least one country");
    }
  }

  private boolean isTrusted(String image) {
    String registry = image.substring(0, image.indexOf('/'));
    return trustedRegistries.contains(registry.toLowerCase(Locale.ROOT));
  }

  private void validateImage(String image, String field, List<String> errors) {
    if (image == null || !DIGEST_IMAGE.matcher(image).matches())
      errors.add(field + " must be pinned by sha256 digest");
    else if (!isTrusted(image)) errors.add(field + " registry is not trusted");
  }

  private static void validateField(
      String key, PluginManifest.ConfigField field, List<String> errors) {
    if (field == null) {
      errors.add("configuration field cannot be null: " + key);
      return;
    }
    if (!key.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}"))
      errors.add("invalid configuration field key: " + key);
    if (field.type() == null || !FIELD_TYPES.contains(field.type()))
      errors.add("unsupported type for configuration field: " + key);
    if (field.label() == null || field.label().isBlank() || field.label().length() > 120)
      errors.add("invalid label for configuration field: " + key);
    if (field.description() != null && field.description().length() > 500)
      errors.add("description too long for configuration field: " + key);
    if (field.secret() != "secret".equals(field.type()))
      errors.add("secret flag/type mismatch for configuration field: " + key);
    if (field.minimumLength() != null && field.minimumLength() < 0
        || field.maximumLength() != null && field.maximumLength() > 10000)
      errors.add("invalid string length bound for configuration field: " + key);
    if (field.minimumLength() != null
        && field.maximumLength() != null
        && field.minimumLength() > field.maximumLength())
      errors.add("inverted string length range for configuration field: " + key);
    if (field.minimum() != null && field.maximum() != null && field.minimum() > field.maximum())
      errors.add("inverted numeric range for configuration field: " + key);
    if ("enum".equals(field.type())
        && (field.options().isEmpty()
            || field.options().size() > 100
            || field.options().stream().anyMatch(option -> option == null || option.isBlank())))
      errors.add("enum requires 1 to 100 non-empty options: " + key);
    if (field.format() != null && !Set.of("email", "uri", "hostname").contains(field.format()))
      errors.add("unsupported format for configuration field: " + key);
    if (field.generatedLength() != null
        && (field.generatedLength() < 16 || field.generatedLength() > 64))
      errors.add("generated secret length must be between 16 and 64: " + key);
  }

  private static boolean isSemver(String version) {
    return version != null && SEMVER.matcher(version).matches();
  }

  private static void validateConfigurationBindings(
      java.util.Map<String, String> bindings,
      java.util.Map<String, PluginManifest.ConfigField> schema,
      String service,
      List<String> errors) {
    bindings.forEach(
        (environmentName, configurationKey) -> {
          if (environmentName == null || !environmentName.matches("[A-Z_][A-Z0-9_]{0,127}")) {
            errors.add(service + " has an invalid environment variable name");
          } else if (environmentName.startsWith("ARTIO_EXTENSION_SERVICE_")) {
            errors.add(service + " cannot override the reserved sibling service address variables");
          }
          if (configurationKey == null || !schema.containsKey(configurationKey)) {
            errors.add(service + " environment binding references an unknown configuration field");
          } else {
            PluginManifest.ConfigField field = schema.get(configurationKey);
            if (field == null || field.type() == null || "json".equals(field.type())) {
              errors.add(service + " environment bindings require a scalar configuration field");
            }
          }
        });
  }

  private static void validatePath(String path, String field, List<String> errors) {
    try {
      URI uri = URI.create(path);
      if (!path.startsWith("/")
          || path.startsWith("//")
          || uri.isAbsolute()
          || path.contains("..")
          || path.length() > 200)
        errors.add(field + " must be a bounded absolute path within the plugin container");
    } catch (RuntimeException ex) {
      errors.add(field + " is invalid");
    }
  }

  private static int compare(String left, String right) {
    String[] l = left.split("[-+]")[0].split("\\."), r = right.split("[-+]")[0].split("\\.");
    for (int i = 0; i < 3; i++) {
      int c = Integer.compare(Integer.parseInt(l[i]), Integer.parseInt(r[i]));
      if (c != 0) return c;
    }
    return 0;
  }
}
