package io.artio.extensions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Declarative, versioned metadata supplied by a Docker extension image. */
public record PluginManifest(
    String manifestVersion,
    String id,
    String name,
    String vendor,
    String version,
    String description,
    String minimumCoreVersion,
    String maximumCoreVersion,
    String image,
    List<Dependency> dependencies,
    List<String> permissions,
    Map<String, ConfigField> configuration,
    List<String> extensionPoints,
    String healthPath,
    String diagnosticsPath,
    GeographicAvailability geographicAvailability,
    List<BundleService> services,
    List<String> persistentPaths,
    Map<String, String> configurationBindings) {

  public PluginManifest {
    Objects.requireNonNull(manifestVersion, "Manifest version is required");
    Objects.requireNonNull(id, "Plugin id is required");
    Objects.requireNonNull(name, "Plugin name is required");
    Objects.requireNonNull(vendor, "Plugin vendor is required");
    Objects.requireNonNull(version, "Plugin version is required");
    Objects.requireNonNull(description, "Plugin description is required");
    Objects.requireNonNull(minimumCoreVersion, "Minimum Core version is required");
    Objects.requireNonNull(maximumCoreVersion, "Maximum Core version is required");
    Objects.requireNonNull(image, "Image identity is required");
    dependencies = immutableNullableList(dependencies);
    permissions = immutableNullableList(permissions);
    configuration = immutableNullableMap(configuration);
    extensionPoints = immutableNullableList(extensionPoints);
    services = immutableNullableList(services);
    persistentPaths = immutableNullableList(persistentPaths);
    configurationBindings = immutableNullableMap(configurationBindings);
  }

  /**
   * Source-compatible constructor for manifests created before primary-container persistent paths.
   */
  public PluginManifest(
      String manifestVersion,
      String id,
      String name,
      String vendor,
      String version,
      String description,
      String minimumCoreVersion,
      String maximumCoreVersion,
      String image,
      List<Dependency> dependencies,
      List<String> permissions,
      Map<String, ConfigField> configuration,
      List<String> extensionPoints,
      String healthPath,
      String diagnosticsPath,
      GeographicAvailability geographicAvailability,
      List<BundleService> services,
      Map<String, String> configurationBindings) {
    this(
        manifestVersion,
        id,
        name,
        vendor,
        version,
        description,
        minimumCoreVersion,
        maximumCoreVersion,
        image,
        dependencies,
        permissions,
        configuration,
        extensionPoints,
        healthPath,
        diagnosticsPath,
        geographicAvailability,
        services,
        List.of(),
        configurationBindings);
  }

  private static <T> List<T> immutableNullableList(List<T> values) {
    return values == null
        ? List.of()
        : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
  }

  private static <K, V> Map<K, V> immutableNullableMap(Map<K, V> values) {
    return values == null
        ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
  }

  /**
   * Source-compatible constructor for bundles declared before configuration-to-environment
   * bindings.
   */
  public PluginManifest(
      String manifestVersion,
      String id,
      String name,
      String vendor,
      String version,
      String description,
      String minimumCoreVersion,
      String maximumCoreVersion,
      String image,
      List<Dependency> dependencies,
      List<String> permissions,
      Map<String, ConfigField> configuration,
      List<String> extensionPoints,
      String healthPath,
      String diagnosticsPath,
      GeographicAvailability geographicAvailability,
      List<BundleService> services) {
    this(
        manifestVersion,
        id,
        name,
        vendor,
        version,
        description,
        minimumCoreVersion,
        maximumCoreVersion,
        image,
        dependencies,
        permissions,
        configuration,
        extensionPoints,
        healthPath,
        diagnosticsPath,
        geographicAvailability,
        services,
        List.of(),
        Map.of());
  }

  /** Source-compatible constructor for manifests created before geographic metadata was added. */
  public PluginManifest(
      String manifestVersion,
      String id,
      String name,
      String vendor,
      String version,
      String description,
      String minimumCoreVersion,
      String maximumCoreVersion,
      String image,
      List<Dependency> dependencies,
      List<String> permissions,
      Map<String, ConfigField> configuration,
      List<String> extensionPoints,
      String healthPath,
      String diagnosticsPath) {
    this(
        manifestVersion,
        id,
        name,
        vendor,
        version,
        description,
        minimumCoreVersion,
        maximumCoreVersion,
        image,
        dependencies,
        permissions,
        configuration,
        extensionPoints,
        healthPath,
        diagnosticsPath,
        GeographicAvailability.global(),
        List.of(),
        List.of(),
        Map.of());
  }

  /** Source-compatible constructor for v1 manifests with geographic metadata. */
  public PluginManifest(
      String manifestVersion,
      String id,
      String name,
      String vendor,
      String version,
      String description,
      String minimumCoreVersion,
      String maximumCoreVersion,
      String image,
      List<Dependency> dependencies,
      List<String> permissions,
      Map<String, ConfigField> configuration,
      List<String> extensionPoints,
      String healthPath,
      String diagnosticsPath,
      GeographicAvailability geographicAvailability) {
    this(
        manifestVersion,
        id,
        name,
        vendor,
        version,
        description,
        minimumCoreVersion,
        maximumCoreVersion,
        image,
        dependencies,
        permissions,
        configuration,
        extensionPoints,
        healthPath,
        diagnosticsPath,
        geographicAvailability,
        List.of(),
        List.of(),
        Map.of());
  }

  public record Dependency(String id, String minimumVersion, String maximumVersion) {}

  /** Optional private, non-invokable sidecar belonging to this plugin installation. */
  public record BundleService(
      String id,
      String image,
      int port,
      String healthPath,
      Map<String, String> configurationBindings,
      List<String> dependsOn,
      List<String> persistentPaths,
      List<Integer> internalPorts,
      Integer memoryLimitMb,
      boolean beforePrimary,
      boolean runToCompletion) {
    public BundleService {
      configurationBindings = immutableNullableMap(configurationBindings);
      dependsOn = immutableNullableList(dependsOn);
      persistentPaths = immutableNullableList(persistentPaths);
      internalPorts = immutableNullableList(internalPorts);
    }

    public BundleService(
        String id,
        String image,
        int port,
        String healthPath,
        Map<String, String> configurationBindings,
        List<String> dependsOn,
        List<String> persistentPaths,
        List<Integer> internalPorts,
        Integer memoryLimitMb) {
      this(
          id,
          image,
          port,
          healthPath,
          configurationBindings,
          dependsOn,
          persistentPaths,
          internalPorts,
          memoryLimitMb,
          false,
          false);
    }

    public BundleService(
        String id,
        String image,
        int port,
        String healthPath,
        Map<String, String> configurationBindings,
        List<String> dependsOn,
        List<String> persistentPaths) {
      this(
          id,
          image,
          port,
          healthPath,
          configurationBindings,
          dependsOn,
          persistentPaths,
          List.of(),
          null);
    }

    public BundleService(
        String id,
        String image,
        int port,
        String healthPath,
        Map<String, String> configurationBindings,
        List<String> dependsOn,
        List<String> persistentPaths,
        List<Integer> internalPorts) {
      this(
          id,
          image,
          port,
          healthPath,
          configurationBindings,
          dependsOn,
          persistentPaths,
          internalPorts,
          null);
    }

    public BundleService(
        String id,
        String image,
        int port,
        String healthPath,
        Map<String, String> configurationBindings) {
      this(
          id,
          image,
          port,
          healthPath,
          configurationBindings,
          List.of(),
          List.of(),
          List.of(),
          null);
    }

    public BundleService(
        String id,
        String image,
        int port,
        String healthPath,
        Map<String, String> configurationBindings,
        List<String> dependsOn) {
      this(
          id,
          image,
          port,
          healthPath,
          configurationBindings,
          dependsOn,
          List.of(),
          List.of(),
          null);
    }

    public BundleService(String id, String image, int port, String healthPath) {
      this(id, image, port, healthPath, Map.of(), List.of(), List.of(), List.of(), null);
    }
  }

  /** Discovery hint only. It never restricts a merchant from enabling another supported market. */
  public record GeographicAvailability(String scope, List<String> countries) {
    public GeographicAvailability {
      countries = immutableNullableList(countries);
    }

    public static GeographicAvailability global() {
      return new GeographicAvailability("GLOBAL", List.of());
    }

    public boolean appliesTo(String country) {
      return "GLOBAL".equals(scope) || country != null && countries.contains(country);
    }
  }

  public record ConfigField(
      String type,
      boolean required,
      boolean secret,
      String label,
      String description,
      String format,
      List<String> options,
      Integer minimumLength,
      Integer maximumLength,
      Double minimum,
      Double maximum,
      boolean generated,
      Integer generatedLength,
      String generatedRotationGroup) {
    public ConfigField {
      options = immutableNullableList(options);
    }

    public ConfigField(
        String type,
        boolean required,
        boolean secret,
        String label,
        String description,
        String format,
        List<String> options,
        Integer minimumLength,
        Integer maximumLength,
        Double minimum,
        Double maximum) {
      this(
          type,
          required,
          secret,
          label,
          description,
          format,
          options,
          minimumLength,
          maximumLength,
          minimum,
          maximum,
          false,
          null,
          null);
    }

    public ConfigField(
        String type,
        boolean required,
        boolean secret,
        String label,
        String description,
        String format,
        List<String> options,
        Integer minimumLength,
        Integer maximumLength,
        Double minimum,
        Double maximum,
        boolean generated,
        Integer generatedLength) {
      this(
          type,
          required,
          secret,
          label,
          description,
          format,
          options,
          minimumLength,
          maximumLength,
          minimum,
          maximum,
          generated,
          generatedLength,
          null);
    }
  }
}
