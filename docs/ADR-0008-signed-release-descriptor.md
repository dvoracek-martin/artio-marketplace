# ADR-0008: Signed immutable official release descriptors

Status: Accepted

## Context

Publisher validation, two-party approval, Zot promotion and a digest-pinned manifest establish release completeness and immutability, but they do not prove that a release came from Artio's approved publication channel. Core must not infer official provenance from a browser-supplied manifest or a Marketplace `official` flag.

## Decision

Marketplace creates a version-1 release descriptor only from the stored manifest after the release is fully `PUBLISHED`. The descriptor includes the logical package/version, the JCS-canonical manifest digest, the exact original manifest and the complete digest-pinned main/sidecar public OCI image inventory. It follows `marketplace-contract/release-descriptor-v1.schema.json` and is signed as RFC 8785 canonical UTF-8 JSON using Ed25519. The envelope follows `signed-release-envelope-v1.schema.json`.

The protected `marketplace-signing` GitHub environment holds the signing private key and invokes `.github/workflows/sign-release.yml` after independent release approval and completed promotion. Marketplace accepts a signature only when it matches the server-derived descriptor and verifies under a configured public trust root; the key identifier and signature are immutable in PostgreSQL. The public descriptor endpoint returns only a stored valid signature for an active, published release. Missing signatures fail closed for official installation while the legacy manifest endpoint remains available for custom/manual workflows.

Core fetches the signed descriptor server-side through its versioned Marketplace proxy and verifies the signer against operator-configured `ARTIO_MARKETPLACE_TRUSTED_KEYS`. Core independently checks descriptor identity, manifest digest, exact main/sidecar image inventory and signature before exposing a verified descriptor to its official installation workflow. Marketplace has no access to customer Core or its Docker controller.

## Key operations

The signing private key exists only in the protected signing environment. Marketplace and Core hold only public Ed25519 SubjectPublicKeyInfo bytes encoded as unpadded base64url and mapped to a stable key ID. Marketplace also uses a separate hash-configured signature-ingest bearer credential. Rotation adds a new key ID to both trust configurations, signs new releases with it, verifies overlap, and only then removes a compromised/retired key. A signing key compromise requires release re-signing under a new key and an explicit security advisory; it must not silently rewrite immutable historical signatures.

## Consequences and open gates

This design adds a Marketplace signature table, public and publisher/signer API routes, key provisioning, and an explicit protected signing workflow. Live acceptance must demonstrate that unpublished, incomplete, unsigned, tampered, wrong-key, and revoked releases cannot enter Core's official install path. This ADR does not complete installation sagas, local archives, advisory/ban enforcement or v2 upgrade work.
