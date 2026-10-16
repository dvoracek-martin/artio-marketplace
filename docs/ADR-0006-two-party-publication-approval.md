# ADR-0006: Require separate approval before publication

**Status:** Accepted
**Date:** 2026-10-16

## Context

Publisher authorization used to allow one bearer token to create and publish a release. OCI digest immutability and Zot repository ACLs prove that bytes exist in the configured registry, but they do not prove who produced those bytes or whether an operator reviewed them. A stolen publisher API token could select an arbitrary digest already present in Zot and make it available to all Core installations that later install the plugin.

## Decision

Publication has two credential-separated steps:

1. A publisher stages a validated immutable Core manifest, catalog metadata, and content-addressed media.
2. An admin-only review endpoint returns the exact staged manifest, catalog, and digest fields. The distinct admin credential approves the release with a reason. PostgreSQL stores SHA-256 values for the manifest and JSONB-normalized catalog content.
3. The publisher credential publishes only if both approved digests still match the staged row. Any catalog update clears approval. CAS row versions prevent approval from racing with an edit or publish.

The publisher, admin, and revocation credential hashes must be pairwise distinct when configured. A publisher credential cannot approve and an admin credential cannot publish. The workflow requires an operator to compare expected OCI digests and provenance out of band.

## Consequences and limits

Compromise of only one Marketplace API token cannot stage, approve, and publish malicious content. This is an operational two-person boundary, not cryptographic signing or identity proof; publisher/admin collusion or compromise of both credentials remains possible. Cosign/Sigstore verification and Core-side signature enforcement are still not implemented. The Core contract and Docker runtime are unchanged.

Marketplace release revocation still only prevents future catalog selection and exact-version installation. It does not notify or stop an already running Core plugin. A signed advisory feed plus a separate Core-side polling/enforcement change is required; Marketplace cannot implement that consumer without changing Core.
