package io.artio.extensions;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Core-owned, closed registry of versioned extension capabilities and their safe invocation
 * policies.
 */
public final class CapabilityRegistry {
  private static final Map<String, Capability> CAPABILITIES =
      Map.of(
          "payment.provider.v1",
              capability(
                  "payment.provider.v1",
                  Map.ofEntries(
                      Map.entry(
                          "listMethods", op("payments.methods.read", Policy.AGGREGATE_READ, false)),
                      Map.entry("createCheckout", op("payment.process", Policy.SELECT_ONE, true)),
                      Map.entry("capture", op("payment.process", Policy.PINNED_ONE, true)),
                      Map.entry("cancel", op("payment.process", Policy.PINNED_ONE, true)),
                      Map.entry("refund", op("payment.process", Policy.PINNED_ONE, true)),
                      Map.entry("reconcile", op("payment.process", Policy.PINNED_ONE, true)),
                      Map.entry("verifyWebhook", op("payment.process", Policy.PINNED_ONE, true)))),
          "shipping.provider.v1",
              capability(
                  "shipping.provider.v1",
                  Map.ofEntries(
                      Map.entry("listMethods", op("shipping.quote", Policy.AGGREGATE_READ, false)),
                      Map.entry("quote", op("shipping.quote", Policy.AGGREGATE_READ, false)),
                      Map.entry("createShipment", op("shipping.label", Policy.SELECT_ONE, true)),
                      Map.entry("purchaseLabel", op("shipping.label", Policy.PINNED_ONE, true)),
                      Map.entry("cancelShipment", op("shipping.label", Policy.PINNED_ONE, true)),
                      Map.entry("track", op("shipping.quote", Policy.PINNED_ONE, false)))),
          "catalog.search.v1",
              capability(
                  "catalog.search.v1",
                  Map.ofEntries(
                      Map.entry("search", op("catalog.read", Policy.AGGREGATE_READ, false)),
                      Map.entry("index", op("catalog.write", Policy.SELECT_ONE, true)),
                      Map.entry("remove", op("catalog.write", Policy.SELECT_ONE, true)),
                      Map.entry("beginRebuild", op("catalog.write", Policy.SELECT_ONE, true)),
                      Map.entry("commitRebuild", op("catalog.write", Policy.PINNED_ONE, true)),
                      Map.entry("abortRebuild", op("catalog.write", Policy.PINNED_ONE, true)))),
          "catalog.enrichment.v1",
              capability(
                  "catalog.enrichment.v1",
                  Map.of("enrich", op("catalog.read", Policy.EXCLUSIVE, false))),
          "notification.channel.v1",
              capability(
                  "notification.channel.v1",
                  Map.of("send", op("notifications.send", Policy.SELECT_ONE, true))),
          "storage.object.v1",
              capability(
                  "storage.object.v1",
                  Map.of(
                      "put", op("storage.object.write", Policy.PRIMARY_REPLICAS, true),
                      "head", op("storage.object.read", Policy.PRIMARY_REPLICAS, false),
                      "get", op("storage.object.read", Policy.PRIMARY_REPLICAS, false),
                      "delete", op("storage.object.delete", Policy.PRIMARY_REPLICAS, true))));

  private CapabilityRegistry() {}

  public static Optional<Capability> find(String capabilityId) {
    return Optional.ofNullable(CAPABILITIES.get(capabilityId));
  }

  public static Set<String> capabilityIds() {
    return CAPABILITIES.keySet();
  }

  private static Capability capability(String id, Map<String, Operation> operations) {
    return new Capability(id, operations);
  }

  private static Operation op(String permission, Policy policy, boolean sideEffecting) {
    return new Operation(permission, policy, sideEffecting);
  }

  public record Capability(String id, Map<String, Operation> operations) {
    public Capability {
      operations = Map.copyOf(operations);
    }

    public Optional<Operation> operation(String operation) {
      return Optional.ofNullable(operations.get(operation));
    }
  }

  public record Operation(String requiredPermission, Policy policy, boolean sideEffecting) {}

  /** FAN_OUT is reserved for fact delivery and is intentionally not an invocation policy here. */
  public enum Policy {
    EXCLUSIVE,
    SELECT_ONE,
    AGGREGATE_READ,
    PRIMARY_REPLICAS,
    PINNED_ONE
  }
}
