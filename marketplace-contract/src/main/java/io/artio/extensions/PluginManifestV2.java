package io.artio.extensions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit v2 declarations layered over the unchanged v1 package and Docker runtime metadata. */
public record PluginManifestV2(
    PluginManifest packageManifest,
    List<CapabilityDeclaration> capabilities,
    List<EventSubscription> subscriptions) {
  public PluginManifestV2 {
    Objects.requireNonNull(packageManifest, "Package manifest is required");
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
  }

  public record CapabilityDeclaration(
      String capabilityId,
      List<String> operations,
      List<String> permissions,
      Map<String, String> accountFingerprints) {
    public CapabilityDeclaration {
      Objects.requireNonNull(capabilityId, "Capability id is required");
      operations = operations == null ? List.of() : List.copyOf(operations);
      permissions = permissions == null ? List.of() : List.copyOf(permissions);
      accountFingerprints =
          accountFingerprints == null ? Map.of() : Map.copyOf(accountFingerprints);
    }
  }

  public record EventSubscription(String eventType, int schemaVersion, List<String> permissions) {
    public EventSubscription {
      Objects.requireNonNull(eventType, "Event type is required");
      permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
  }
}
