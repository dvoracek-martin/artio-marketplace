# Artio plugin manifest v2

v2 adds explicit capability operations and durable event subscriptions around the existing package and Docker runtime contract. v1 remains accepted by its existing validator and controller. The Core does not derive capability declarations from a v1 `extensionPoints` list; each v1 registration is represented as an explicit `LEGACY_V1` compatibility instance whose runtime identity remains its `pluginId`. In development there is one current database snapshot, so recreating the development database is required after schema changes; persistent production upgrade support begins with production releases.

The Core-owned operation registry is [plugin-capability-registry-v1.json](plugin-capability-registry-v1.json). The strict JSON shape is [plugin-manifest-v2.schema.json](plugin-manifest-v2.schema.json). Runtime validation additionally checks every capability, operation, permission, subscription event and schema version against the compiled allowlists. Unknown values fail closed. A manifest declaration does not select an instance or authorize a store scope; that requires a Core-created binding.

```json
{
  "packageManifest": {
    "manifestVersion": "2",
    "id": "payments.example",
    "name": "Example Payments",
    "vendor": "Example Ltd",
    "version": "2.0.0",
    "description": "Payment provider adapter",
    "minimumCoreVersion": "0.1.0",
    "maximumCoreVersion": "9.0.0",
    "image": "ghcr.io/example/payments@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "extensionPoints": [],
    "healthPath": "/health",
    "diagnosticsPath": "/diagnostics",
    "geographicAvailability": {"scope": "GLOBAL", "countries": []}
  },
  "capabilities": [{
    "capabilityId": "payment.provider.v1",
    "operations": ["listMethods", "createCheckout", "capture", "refund", "reconcile"],
    "permissions": ["payments.methods.read", "payment.process"],
    "accountFingerprints": {"createCheckout": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
  }],
  "subscriptions": [{"eventType": "order.paid", "schemaVersion": 1, "permissions": []}]
}
```

Account fingerprints are Core-generated stable SHA-256 identifiers for an external merchant account, not credentials. Credentials remain encrypted in Core and are injected through the current isolated Docker controller. They are required on a binding whenever the capability targets an external account. Event subscriptions carry only the event's frozen schema payload; subscription declarations cannot grant additional permissions or request arbitrary PII.

An `events.subscriber.v1` handler must return `{"accepted":true,"deliveryId":"<delivery UUID>"}` for the exact idempotency key Core supplied. A transport-level 2xx without this matching acknowledgement is retried and remains pending; delivery is never marked complete based only on HTTP acceptance.

Operation policies are Core-owned: read-only discovery may aggregate; a checkout or new shipment chooses one eligible target; refunds, capture, cancel, reconciliation and historical shipment actions are pinned to the persisted owner; object writes use a primary and verified replicas. `FAN_OUT` is reserved for event facts and cannot be used to invoke an operation. Routing considers an explicit bounded scope and priority, and equal-specificity exclusive matches reject atomically.

This contract is foundational, not end-to-end acceptance by itself. See ADR 0004 and the v5.1 phase gates in [ROADMAP.md](../ROADMAP.md).
