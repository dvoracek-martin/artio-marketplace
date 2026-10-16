# ADR-0007: Private staging and approved OCI promotion

**Status:** Accepted; live Zot verification required before closure  
**Date:** 2026-10-16

## Context

The earlier publisher path wrote plugin artifacts directly into `public/**`, where Zot allows anonymous reads. Human approval at the Marketplace API did not protect direct OCI tag, digest, blob, or cross-repository mount requests. Hiding an unapproved release from catalog APIs was not an adequate artifact boundary.

## Decision

Keep Zot and provision static repository ACLs at deployment:

- `staging/**`: publisher and promoter may read/create/update; anonymous access is denied.
- `public/**`: anonymous read is allowed; only the dedicated promoter may read/create/update.
- Other repositories default deny. No publisher or promoter may delete artifacts.

Publisher clients push into `staging/plugins/...`. The immutable runtime manifest retains final `public/plugins/...@sha256:...` references. Marketplace validates those references against the corresponding private staging paths, records a separate admin approval over the exact manifest and catalog hashes, and creates a durable `publication_promotions` job. The job copies OCI indexes/platform manifests and config/layer blobs using authenticated OCI Distribution requests, verifies source and destination content digests, rechecks release moderation, then updates the promotion and release rows in one PostgreSQL transaction. A crash or failed copy leaves the release unavailable in the catalog and eligible for digest-idempotent retry after the existing lease expires.

Zot cannot atomically expose every image in a multi-image release bundle using static repo ACLs. Once a human has approved the frozen bundle, some public bytes may become readable while the remaining images are still being copied. Core cannot install the bundle from Marketplace until the complete release is finalized and catalog-visible. This accepted boundary does not permit any unapproved image to be publicly pulled. If a future requirement explicitly requires bundle-atomic public reads, add a separately reviewed narrow distribution gate rather than dynamic per-release Zot ACL mutation.

## Consequences and limits

Marketplace has no customer Docker socket, no customer runtime credentials, and no remote Core connection. The publisher credential is not a release signer, and OCI digest checks are not cryptographic provenance. Signed immutable release descriptors, Core signature verification, status advisories, and local archive import remain open requirements from the security directive; this ADR does not claim them implemented.

Promotion is retryable by immutable digest. A partially copied approved bundle may retain public OCI bytes after a failure; it remains non-catalog-visible until every declared artifact has passed final public verification. Registry blob retention and release install eligibility remain separate.

## Verification required

The live gate must use the pinned Zot version and both gateway listeners to verify anonymous staging manifest/tag/blob denial, publisher public-write denial, promoter staging-to-public promotion, exact digest preservation for main and sidecars/indexes, failure/restart before finalization, catalog invisibility during partial promotion, and clean removal of only the isolated test project's containers, image tags, network, and volumes.
