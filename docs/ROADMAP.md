# Marketplace ↔ Core integration security roadmap

This is the Marketplace-side progress ledger for the approved 2026-10-16 integration directive. It supplements the Artio Core v5.1 roadmap and preserves the existing Java 25, Zot, OCI, Core-owned lifecycle, and single development REST surface (`/api/v1`). It does not mark the system production-ready.

## Current checkpoint: private staging and approved OCI promotion

| Requirement | Status | Evidence / remaining work |
|---|---|---|
| Zot static ACLs: private `staging/**`, anonymous read-only `public/**`, dedicated promoter, default deny | PASS | Pinned Zot 2.1.21 live acceptance checked both gateway listeners; anonymous staging access and publisher public writes were denied. |
| Promotion after independent approval, durable job, completeness before `PUBLISHED` | PASS for normal flow | Full Docker acceptance verified separate publisher/admin roles, job state `PUBLISHED:1`, final main/sidecar pulls and availability after Marketplace restart. Fault-injected interrupted copy/restart and concurrent moderation still need tests. |
| Nested OCI index and platform preservation | PASS | The v2 main image was an OCI index with `linux/amd64` and `linux/arm64`. Zot preserved the index and child digests; Docker pulled both platforms. An infrastructure unit test verifies recursive index copying. |
| No unapproved descriptor/catalog exposure and no public access to new unapproved artifacts | PASS within tested Zot boundary | Acceptance denied the new release manifest before finalization across both listeners and tested digest reuse without granting release install eligibility. Partially public bytes after approval remain the explicitly documented Zot static-ACL limitation. |
| Signed immutable release descriptor and Core-side official provenance verification | PASS for the local trust path | Live Docker acceptance signed four releases, rejected unsigned descriptors, and verified Core's trusted-key, canonical manifest digest, route identity and exact OCI image inventory checks while importing the Marketplace manifest. Production signing-key rotation and recovery drill remains OPEN. |
| Core durable installation saga, custom registry, and secured local OCI/Docker archive import | OPEN | Core remains authoritative for install/runtime. Do not route arbitrary client payloads through the official trust path. |
| Signed support/removal/security advisory feed and local digest-level ban enforcement | OPEN | Core must enforce bans against every runtime path and already-running container while preserving business obligations. No remote Core control channel. |
| Per-instance v2 upgrade/rollback, status-aware Backoffice, and job diagnostics | OPEN | Keep source, support, security and lifecycle dimensions separate. |
| Exact compatible Core pin and required cross-repository CI acceptance | PASS (2026-10-16) | Core `bdd1b80d8f020eca05e3000b6435f18aa835d8bd` is published and pinned in CI after the local signed-descriptor/Core acceptance passed. GitHub Actions run [38930669081](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38930669081) passed the exact Core pin, Gradle/architecture/coverage checks, OpenAPI lint, V1–V5 database acceptance and live Docker Zot ACL/public API/restore-and-pull acceptance. The first run exposed missing arm64 emulation; QEMU is now registered with a digest-pinned action, and the retry passed both OCI platforms. |

## Verified local checkpoint (2026-10-16)

- `./gradlew.bat spotlessCheck build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification --no-daemon` — PASS.
- `bash ./scripts/docker-acceptance.sh` against the uniquely named Zot/Marketplace stack — PASS, including private staging, two-party review, approved OCI promotion, multi-platform index, sidecar, SemVer/catalog/moderation/revocation, restricted plugin invocation, outage behavior and proxy limits.
- `scripts/run-signed-descriptor-acceptance.ps1` — PASS, including Ed25519 release signing and verified Core-side Marketplace import; Core's selected Docker runtime integration suite passed (fixture-gated egress test skipped), and `:artio-rest-api:test` passed. The named acceptance project's containers, network and three volumes, locally built `artio-marketplace:local` image, generated OCI test tags and temporary signing credentials were removed; pre-existing Docker resources were retained.
- `./gradlew.bat :artio-rest-api:integrationTest --tests io.artio.api.extensions.DockerExtensionRuntimeIntegrationTests --tests io.artio.api.catalog.CatalogApiIntegrationTests.extensionLifecycleEnforcesDependencyStateAndVersionBeforeRuntimeCalls --tests io.artio.api.catalog.CatalogApiIntegrationTests.importsPublishedMarketplaceManifestThroughCoreApiWhenAcceptanceIsConfigured --no-daemon` — the integration task ran 6 tests: 5 passed, 1 fixture-gated egress test skipped. A later invocation reported the combined build successful after the Core API unit suite passed 162/162.
- Core `:artio-rest-api:test` — PASS, 162 tests.
- Docker cleanup targeted only the two uniquely named Marketplace acceptance projects created during this checkpoint and the exact test-owned images/tags and temporary signing-credential directories. No global prune was used. The unrelated existing Artio development stack was left running.

## Next dependency order

1. Finalize the shared canonical signed-release descriptor and signed-advisory contracts plus threat model.
2. Implement an independent release signing authority and immutable Marketplace descriptor issuance only after every promoted artifact verifies.
3. Verify signed official provenance in Core's server-side install path; preserve manual custom plugin registration as `CUSTOM`, never as official.
4. Implement the durable Core installation service and isolated-controller job protocol; add private-registry and quarantined local archive channels.
5. Implement signed advisory ingestion, exact artifact identity, ban containment and recovery semantics.
6. Complete v2 per-instance upgrade/rollback, Backoffice status/job UX, and fault-injected recovery acceptance.
7. Pin and test the exact mutually compatible commits in fail-closed CI, then continue the remaining Master Plan in dependency order.
