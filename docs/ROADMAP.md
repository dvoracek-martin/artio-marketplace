# Marketplace ↔ Core integration security roadmap

This is the Marketplace-side progress ledger for the approved 2026-10-16 integration directive. It supplements the Artio Core v5.1 roadmap and preserves the existing Java 25, Zot, OCI, Core-owned lifecycle, and single development REST surface (`/api/v1`). It does not mark the system production-ready.

## Current checkpoint: private staging and approved OCI promotion

| Requirement | Status | Evidence / remaining work |
|---|---|---|
| Zot static ACLs: private `staging/**`, anonymous read-only `public/**`, dedicated promoter, default deny | PASS | Pinned Zot 2.1.21 live acceptance checked both gateway listeners; anonymous staging access and publisher public writes were denied. |
| Promotion after independent approval, durable job, completeness before `PUBLISHED` | PASS for normal flow | Full Docker acceptance verified separate publisher/admin roles, job state `PUBLISHED:1`, final main/sidecar pulls and availability after Marketplace restart. Fault-injected interrupted copy/restart and concurrent moderation still need tests. |
| Nested OCI index and platform preservation | PASS | The v2 main image was an OCI index with `linux/amd64` and `linux/arm64`. Zot preserved the index and child digests; Docker pulled both platforms. An infrastructure unit test verifies recursive index copying. |
| No unapproved descriptor/catalog exposure and no public access to new unapproved artifacts | PASS within tested Zot boundary | Acceptance denied the new release manifest before finalization across both listeners and tested digest reuse without granting release install eligibility. Partially public bytes after approval remain the explicitly documented Zot static-ACL limitation. |
| Signed immutable release descriptor and Core-side official provenance verification | OPEN | Digest validation and admin approval are not cryptographic provenance. Define a canonical versioned contract, independent signing authority/trust roots/key rotation, immutable descriptor storage, and strict Core verification before official install. |
| Core durable installation saga, custom registry, and secured local OCI/Docker archive import | OPEN | Core remains authoritative for install/runtime. Do not route arbitrary client payloads through the official trust path. |
| Signed support/removal/security advisory feed and local digest-level ban enforcement | OPEN | Core must enforce bans against every runtime path and already-running container while preserving business obligations. No remote Core control channel. |
| Per-instance v2 upgrade/rollback, status-aware Backoffice, and job diagnostics | OPEN | Keep source, support, security and lifecycle dimensions separate. |
| Exact compatible Core pin and required cross-repository CI acceptance | OPEN | The local Core acceptance passed for Marketplace v2 import and Docker lifecycle. Update Marketplace CI only after a mutually compatible Core commit is published and its mandatory acceptance is green. |

## Verified local checkpoint (2026-10-16)

- `./gradlew.bat spotlessCheck build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification --no-daemon` — PASS.
- `bash ./scripts/docker-acceptance.sh` against the uniquely named Zot/Marketplace stack — PASS, including private staging, two-party review, approved OCI promotion, multi-platform index, sidecar, SemVer/catalog/moderation/revocation, restricted plugin invocation, outage behavior and proxy limits.
- `./gradlew.bat :artio-rest-api:integrationTest --tests io.artio.api.extensions.DockerExtensionRuntimeIntegrationTests --tests io.artio.api.catalog.CatalogApiIntegrationTests.extensionLifecycleEnforcesDependencyStateAndVersionBeforeRuntimeCalls --tests io.artio.api.catalog.CatalogApiIntegrationTests.importsPublishedMarketplaceManifestThroughCoreApiWhenAcceptanceIsConfigured --no-daemon` — the integration task ran 6 tests: 5 passed, 1 fixture-gated egress test skipped. A later invocation reported the combined build successful after the Core API unit suite passed 162/162.
- Core `:artio-rest-api:test` — PASS, 162 tests.
- Docker cleanup used only project `artio-marketplace-staging-promotion-accept`; its containers, network, three named volumes and locally built `artio-marketplace:local` image were removed. No global prune was used. Docker checks found no project resources or local test tags remaining. The unrelated existing Artio development stack was left running.

Temporary acceptance credentials remain in the exact local temporary test directory because the desktop command review rejected the requested local file deletion. They were never tracked or included in either repository. Remove that directory manually after reviewing the test run if desired.

## Next dependency order

1. Finalize the shared canonical signed-release descriptor and signed-advisory contracts plus threat model.
2. Implement an independent release signing authority and immutable Marketplace descriptor issuance only after every promoted artifact verifies.
3. Verify signed official provenance in Core's server-side install path; preserve manual custom plugin registration as `CUSTOM`, never as official.
4. Implement the durable Core installation service and isolated-controller job protocol; add private-registry and quarantined local archive channels.
5. Implement signed advisory ingestion, exact artifact identity, ban containment and recovery semantics.
6. Complete v2 per-instance upgrade/rollback, Backoffice status/job UX, and fault-injected recovery acceptance.
7. Pin and test the exact mutually compatible commits in fail-closed CI, then continue the remaining Master Plan in dependency order.
