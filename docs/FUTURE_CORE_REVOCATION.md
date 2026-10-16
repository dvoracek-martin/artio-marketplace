# Future signed revocation advisory contract

This document records a future interoperability boundary. Marketplace does not currently publish this feed, Artio Core does not currently consume it, and there is no remote kill switch. The document alone is not a claim of signing, delivery, or enforcement.

## Intended purpose

Give independent Core installations an authenticated, cacheable way to learn that a specific plugin release is no longer recommended for a new installation. It must not uninstall, stop, disable, or alter an already installed plugin. An operator in each Core installation remains responsible for local lifecycle actions.

## Proposed feed envelope

The eventual feed should be a versioned JSON envelope containing:

- `schemaVersion` and monotonically increasing `sequence`;
- `issuedAt` and `expiresAt` in UTC;
- `keyId` identifying a preconfigured Marketplace signing key;
- an ordered list of entries keyed by immutable `pluginId` plus exact SemVer `version`, each with a bounded category, human-readable reason, and `revokedAt`;
- a detached signature over the exact UTF-8 canonical payload bytes.

Core must pin the permitted public signing key out of band, verify signature and expiry before accepting entries, reject sequence rollback, and cache the last verified snapshot. Unknown keys, malformed payloads, expired data, and network failure must not execute an action. A valid entry can block a future install/update selection for that exact release. It cannot be a command channel and must never cause automatic removal or runtime shutdown.

## Rollout gates

Before implementation, define a canonical JSON signing format and key rotation procedure; add a public versioned read-only Marketplace endpoint; publish an OpenAPI schema and threat model; implement independent Core verification and local policy; add cross-repository fixtures and key compromise drills; and test offline behavior. Marketplace signing keys must never be derived from publisher/admin/revocation bearer tokens.
