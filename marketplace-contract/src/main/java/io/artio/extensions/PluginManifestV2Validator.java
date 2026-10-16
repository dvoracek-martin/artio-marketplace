package io.artio.extensions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates explicit v2 capability and event subscription declarations against Core-owned
 * allowlists.
 */
public final class PluginManifestV2Validator {
  private static final Set<String> EVENT_TYPES =
      Set.of(
          "order.created",
          "order.paid",
          "order.cancelled",
          "order.shipped",
          "order.delivered",
          "order.refunded",
          "catalog.product.published",
          "catalog.product.archived");

  public List<String> validate(PluginManifestV2 manifest) {
    List<String> errors = new ArrayList<>();
    if (manifest == null) return List.of("manifest is required");
    if (!"2".equals(manifest.packageManifest().manifestVersion())) {
      errors.add("v2 package manifestVersion must be 2");
    }
    Set<String> seenCapabilities = new HashSet<>();
    for (var declaration : manifest.capabilities()) {
      if (declaration == null) {
        errors.add("capability declaration cannot be null");
        continue;
      }
      if (!seenCapabilities.add(declaration.capabilityId()))
        errors.add("duplicate capability declaration: " + declaration.capabilityId());
      var capability = CapabilityRegistry.find(declaration.capabilityId());
      if (capability.isEmpty()) {
        errors.add("unsupported capability: " + declaration.capabilityId());
        continue;
      }
      Set<String> operations = new HashSet<>();
      for (String operationName : declaration.operations()) {
        if (!operations.add(operationName))
          errors.add(
              "duplicate capability operation: "
                  + declaration.capabilityId()
                  + "/"
                  + operationName);
        var operation = capability.get().operation(operationName);
        if (operation.isEmpty())
          errors.add("unsupported operation: " + declaration.capabilityId() + "/" + operationName);
        else if (!declaration.permissions().contains(operation.get().requiredPermission())) {
          errors.add(
              "capability operation requires declared permission: "
                  + operation.get().requiredPermission());
        }
      }
      for (String permission : declaration.permissions()) {
        if (operations.stream()
            .noneMatch(
                name ->
                    capability
                        .get()
                        .operation(name)
                        .map(op -> op.requiredPermission().equals(permission))
                        .orElse(false))) {
          errors.add("permission is not used by a declared operation: " + permission);
        }
      }
      declaration
          .accountFingerprints()
          .forEach(
              (operationName, fingerprint) -> {
                if (!operations.contains(operationName))
                  errors.add(
                      "account fingerprint references an undeclared operation: " + operationName);
                if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
                  errors.add("account fingerprint must be a lowercase sha256 value");
              });
    }
    Set<String> subscriptions = new HashSet<>();
    for (var subscription : manifest.subscriptions()) {
      if (subscription == null) {
        errors.add("event subscription cannot be null");
        continue;
      }
      String key = subscription.eventType() + "/" + subscription.schemaVersion();
      if (!subscriptions.add(key)) errors.add("duplicate event subscription: " + key);
      if (!EVENT_TYPES.contains(subscription.eventType()))
        errors.add("unsupported event subscription: " + subscription.eventType());
      if (subscription.schemaVersion() != 1)
        errors.add("unsupported event schema version: " + subscription.schemaVersion());
      if (!subscription.permissions().isEmpty())
        errors.add("event subscriptions do not grant data permissions");
    }
    return List.copyOf(errors);
  }
}
