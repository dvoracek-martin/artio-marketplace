# Acceptance evidence

This status distinguishes successful checks from failures and checks that have not run. A green job does not override a failed check inside a `continue-on-error` step.

## Current local verification (2026-10-17)

On the current local `main` candidate, `./gradlew.bat --no-daemon --max-workers=1 --rerun-tasks spotlessCheck build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification` passed (`BUILD SUCCESSFUL`, 41 tasks executed). This reran formatting, unit tests, architecture checks, JaCoCo reports and domain coverage without relying on cached test results. It does not run PostgreSQL/Zot integration tests or publish OCI images; the latest live Compose acceptance remains the separately recorded evidence below.

The already-running local development stack also answered readiness `UP` on port 18081. Its public CH catalog returned `storage.artio-minio` 1.1.2 and its descriptor endpoint returned a signed descriptor with five image references from the local Zot registry on port 15000. This is local development publication only; it is not public registry or independent Marketplace release approval. The check was read-only and did not recreate or alter the stack.

## Pre-hardening baseline

Commit `7de67c23bd16f87687c1b6fc7ce530354fd534a7` was pushed to `main`. GitHub Actions run [38827486941](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38827486941) completed successfully. It verified Java 25.0.4, Gradle 9.8.1, formatting, unit/architecture tests, JaCoCo, OpenAPI lint, a live Compose/PostgreSQL/Zot stack, the Docker acceptance script, the backup/restore drill, SPDX SBOM generation, Trivy, and stack cleanup.

These results describe the predecessor implementation before the hardening work. At that baseline, the live acceptance shell script recorded a failure from one pinned Artio Core packaged-controller test and the workflow used `continue-on-error`. The Marketplace CI initially made that failure required. Investigation showed that the failing controller test is Core-owned and independent of Marketplace publication/import behavior; the Marketplace acceptance command now retains the passing Core Docker runtime and Marketplace catalog/API tests while reporting the packaged-controller failure as an unresolved Core-owned boundary, not a Marketplace pass.

## Post-hardening local candidate evidence

The source candidate is based on Marketplace hardening baseline `0f1d76e42908978911c7b10667827e033b38bc19`. Local verification on 2026-10-16 completed the Java 25 unit/architecture tests (including digest-verifier tests), Redocly OpenAPI lint, V1-to-V2 migration with pre-existing ambiguous rows, acceptance fail-closed self-check, full Compose/PostgreSQL/Zot Docker acceptance, PostgreSQL/Zot/media backup/restore with an exact manifest comparison and digest pull, and direct sensitive Actuator deny checks. The live acceptance output reached its final `Docker acceptance passed` line. Current detailed finding-by-finding status and residual risks are in [post-implementation hardening](POST_IMPLEMENTATION_HARDENING.md).

The exact combined local `spotlessCheck clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification` command failed in this Windows Docker environment when google-java-format threw `NoClassDefFoundError` for Guava nested classes during `spotlessJavaCheck`; separate clean-home `spotlessCheck` passed, and `clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification -x spotlessCheck` passed. Hosted CI passed the combined compile/format/unit/architecture/coverage step. No pinned Core test result is inferred from local Marketplace acceptance.

## Zot private staging and multi-platform promotion acceptance (2026-10-16)

The updated `scripts/docker-acceptance.sh` passed against the pinned Zot 2.1.21 in isolated Compose project `artio-marketplace-staging-promotion-accept`. It built a two-platform OCI image index using Docker's manifest-list tooling, staged it with the publisher, independently approved the frozen release, and let Marketplace promote its main index, both platform manifests, sidecar and blobs. Both public manifest digests were preserved. Anonymous pulls of staging tags/digests and writes to public remained denied across both public and publisher gateway listeners; publisher could not write to public. Docker pulled the public main image for both `linux/amd64` and `linux/arm64`. Promotion rows ended `PUBLISHED:1`, and unapproved release manifests stayed unavailable. The full acceptance also passed its existing SemVer, moderation, revocation, proxy, restricted invocation and Marketplace-outage checks.

The matching Core command fetched and registered the Marketplace v2 manifest and exercised real Docker install/invoke/update/rollback, sidecar health and data retention, and two-instance isolation against the promoted OCI index. The selected Integration task ran 6 tests: 5 passed and the fixture-gated egress test was skipped. The API unit suite passed 162/162 after its persisted-permission fixtures were updated with the required `tokenVersion` claim and `token_version` DB field. Marketplace `spotlessCheck build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification` passed.

## Signed Zot 2.1.22 to Marketplace to Core acceptance (2026-10-16)

The live signed-descriptor acceptance passed against the pinned signed Zot 2.1.22 image index. It verified anonymous public pulls and denied writes, authenticated OCI publication, digest retention after tag overwrite, multi-platform artifacts, catalog filtering and localization, separate approval roles, signed advisory revocation, Core manifest import and restricted plugin invocation. Core's real Docker runtime tests passed install/invoke/update/rollback, sidecar health and data retention, same-release instance isolation, and exact-digest revocation. The acceptance script's exit trap removed its test containers, images, files, volumes and Compose network; a follow-up check found no run-named containers or volumes. The updated image awaits the Marketplace CI Trivy result.

Cleanup removed only the acceptance project's PostgreSQL, Zot and catalog-assets volumes, its network and containers, the locally built `artio-marketplace:local` image, and reference image tags/digests created by acceptance. A final Docker check found no containers, volumes or test tags belonging to that project. Signed release descriptors/Core verification, interrupted-promotion recovery and deliberate catalog-invisibility fault injection remain open; this evidence closes the Zot-native/index-copy gate only.

## Results

| Area | Status | Evidence |
|---|---|---|
| Java 25 compile, formatting, unit tests, architecture boundaries, JaCoCo | PASS | CI run 38825213413, `Compile, format, unit and architecture checks, coverage`; success on the source commit above. Local Java 25 container command `./gradlew --no-daemon clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification -x spotlessCheck` also succeeded. |
| Runtime dependency alignment | PASS | Java 25 Gradle dependency report resolves Jackson annotations `2.22`, Jackson 2 core/databind `2.21.7`, Jackson 3 core/databind `3.2.3`, and Tomcat `11.0.25`. Live app startup in CI passed after this change. |
| OpenAPI 3.1 contract | PASS | CI ran `npx --yes @redocly/cli@1.34.5 lint marketplace-contract/openapi.yaml`; successful. |
| Marketplace + PostgreSQL + Zot startup, health, and persistence | PASS | CI Compose stack became healthy; live checks persisted and reread the published releases after Marketplace restart. |
| Zot ACL and approved OCI promotion | PASS (signing still open) | Live acceptance denied anonymous staging reads and public writes, allowed authenticated publisher staging pushes, promoted approved main/sidecar content, and verified an OCI index plus both platform manifests kept their exact digests. It confirmed `PUBLISHED:1` only after the bundle was available. See the multi-platform run above. |
| Public catalog, locales, release versions, manifest and media | PASS | Live `/api/v1/marketplace/...` checks covered localized listing/category reads and original release manifest retrieval; listing has uploaded icon and screenshot media. Catalog metadata remains separate from the Core manifest. |
| Runnable reference extension | PASS | Acceptance pulled and ran the published digest in a restricted container. Health/diagnostics and authenticated Core v1 invocation worked; missing token returned 401 and retry was deterministic. |
| Real OCI digest pull and multi-image/update/rollback runtime | PASS | The pinned Core `DockerExtensionRuntimeIntegrationTests` passed `installsInvokesUpdatesAndRollsBackRealRestrictedContainers` and `managesSidecarHealthAndPreservesDataAcrossBundleUpdateAndRollback` using the Marketplace-published image references. Anonymous Docker pull and denied push also passed. |
| Core catalog dependency/version check | PASS | Pinned Core `CatalogApiIntegrationTests.extensionLifecycleEnforcesDependencyStateAndVersionBeforeRuntimeCalls` passed. |
| Core packaged controller flow | NOT RUN (Core-owned) | It failed in historical GitHub run 38906863505, against the older Core pin, at `PackagedExtensionControllerIntegrationTests.java:146` with `NullPointerException` while dereferencing bundle health. Core was not changed. This separate Core-owned test is excluded from Marketplace CI; its behavior remains unverified and is not reported as passing. |
| Core egress integration test | NOT RUN | `DockerExtensionRuntimeIntegrationTests.restrictsRealPluginEgressToTheProviderGateway` was skipped by its test fixture. |
| Offline-after-install | PARTIAL | CI stopped the Marketplace service while the independently running reference extension remained healthy and returned the same authenticated invocation result. A full Core Plugin Manager offline install/update failure-diagnostic path was not run. |
| Backup/restore drill | PASS | CI run 38827486941 backed up PostgreSQL, Zot and media after a controlled Marketplace restart; destroyed only its unique acceptance project's volumes; restored fresh volumes; confirmed the published manifest was byte-for-byte identical and pulled its image by digest. |
| Full Core Admin Plugin Manager install/register/enable/uninstall using the exact published manifest | NOT RUN | The isolated pinned Core runtime and catalog tests cover real Docker lifecycle and catalog policy, but the full admin-controller flow did not pass. No deployed customer Core is assumed or contacted. |
| Marketplace manifest through Core discovery, validation and registration with local Zot | v1 PASS; v2 API import PASS (hosted run 38911076975) | CI fetched the published v2 manifest from Marketplace and the exact Core test `importsPublishedMarketplaceManifestThroughCoreApiWhenAcceptanceIsConfigured` passed against Core `9dbc406fa351432a596f0cae1067009d53121db5`; the script also asserts that the test appears in Gradle's XML report so an unmatched filter cannot silently pass. A separate local Zot run registered and uninstalled the same v2 envelope through Core's `/api/v1/admin/extensions/register-v2`. A direct Marketplace-v2-to-Core Docker install/enable/invocation has not been separately run. |
| PostgreSQL catalog ordering/filter reproduction | PASS locally and hosted | The actual `JdbcCatalogQuery.plugins()` adapter ran against the V1–V3 Flyway schema on PostgreSQL 16 in `JdbcCatalogQueryPostgresIntegrationTest`. It verifies compatible fallback (1.0.0 for Core 1.5.0), SemVer ordering over publication timestamps (2.0.0 over later-published 1.0.1), release-scoped revocation preserving a sibling, and plugin bans remaining in force when a later release is added. Java/format/unit/coverage and migrations passed in hosted run [38911076975](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38911076975); that run's live Docker acceptance also passed. |
| Local Zot tag-overwrite digest retention | PASS | Hosted run 38874748475 overwrote the `1.2.0` tag with different image content, verified that the tag resolved to a new digest, removed the old local digest image, and successfully pulled the prior published digest from Zot. |
| Local Windows end-to-end Docker acceptance | PASS (2026-10-16) | The full isolated Zot/Marketplace acceptance passed including outage behavior and OCI index promotion; only this task's named project resources were removed. A separate backup/restore drill was not part of this run. |
| Scaling to thousands of independent Core installations | NOT RUN | Architecture has no per-customer Core connections or Docker socket, but no representative load profile or throughput test has been performed. |

## Latest verified Marketplace CI

GitHub Actions run [38911076975](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38911076975) passed on Marketplace commit `86b2945307ae6f526bb8832c3811c84d5c047ea2` with Core pin `9dbc406fa351432a596f0cae1067009d53121db5`. Java 25 compile/format/unit/architecture/coverage, OpenAPI lint, V1–V3 migration, the fail-closed check, live Zot/Marketplace Docker acceptance, the exact Marketplace-manifest-to-Core v2 import test, Core Docker runtime tests, backup/restore, SBOM, artifact upload, Trivy scan and ephemeral Compose cleanup all passed. The Core egress test was skipped by its own fixture guard. The separate Core packaged-controller test remains outside this Marketplace gate and is not counted as passing.

## Post-hardening GitHub Actions run

Hardening commit `e9bca63db6fa5653e1909bec8135739d907d0ba8` was pushed to `main`. GitHub Actions run [38833700448](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38833700448) completed with **failure**. This is the expected fail-closed result for the unresolved pinned Core integration test, not a green Marketplace release gate:

- Java 25 compile, formatting, unit/architecture tests, and coverage: **PASS**.
- OpenAPI lint, V1-to-V2 migration, acceptance-gate failure injection, and Marketplace/PostgreSQL/Zot startup: **PASS**.
- Live Docker acceptance shell (Zot ACL, OCI publication, public APIs, restricted reference plugin, Marketplace outage, and rate limit): **PASS**, ending with `Docker acceptance passed`.
- The encompassing required CI step then ran pinned Core compatibility tests and **FAIL**ed because `PackagedExtensionControllerIntegrationTests.packagedControllerAuthenticatesAndManagesPrivateBundleWithoutCommerceServices` failed at Core test line 146 with `NullPointerException` while dereferencing `remote.health(current).healthy()`. The Marketplace workflow checks out Core in a separate workspace; Core was not changed.
- Isolated PostgreSQL/Zot/media backup and restore with manifest comparison and digest pull: **PASS**, including after the acceptance failure.
- SPDX SBOM generation: **PASS**; artifact upload: **SKIPPED** because the required acceptance gate failed.
- Trivy HIGH/CRITICAL image scan and ephemeral stack teardown: **PASS**.

The hosted runs confirm that the CI gate now reports the pinned Core failure instead of suppressing it, even though the Marketplace Docker acceptance itself passes. The Core controller flow remains unverified as passing and needs a separately scoped Core fix. The Marketplace changes are pushed, but this workflow is red and the release gate is not green.

## Two-party publication approval run

Commit `3dccd9ffd828e4bab55e68bf86a62ec354919660` added a separate admin approval step bound to the staged manifest and catalog digests. GitHub Actions run [38869591460](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38869591460) completed with **failure** at the pinned Core compatibility test, while the Marketplace gates behaved as follows:

- Java 25 compile, formatting, unit/architecture tests and coverage: **PASS**.
- OpenAPI lint and V1–V3 PostgreSQL migration test: **PASS**.
- Fail-closed acceptance self-check and Marketplace/PostgreSQL/Zot startup: **PASS**.
- Docker acceptance, including publisher/admin role separation, unapproved publish rejection, approval invalidation after catalog edits, re-approval, OCI publication and digest pulls: **PASS**, final output `Docker acceptance passed`.
- Pinned Core compatibility: **FAIL**, solely at `PackagedExtensionControllerIntegrationTests.packagedControllerAuthenticatesAndManagesPrivateBundleWithoutCommerceServices`, Core line 146 (`NullPointerException`). Core was not changed.
- Isolated backup/restore drill, SPDX SBOM generation, Trivy HIGH/CRITICAL scan and `docker compose down -v` cleanup: **PASS**. Verified build/SBOM artifact upload was **SKIPPED** because the required gate was red.

The latest Marketplace Docker acceptance therefore verifies the two-party publication behavior, but the full workflow is not green and this source is not fully release-ready while the pinned Core test fails. The two-party human approval boundary does not replace cryptographic image signing/provenance. See [post-implementation hardening](POST_IMPLEMENTATION_HARDENING.md) for the remaining A1/A2 and other risks.

## Development regression additions

Commit `32e30846b2324136c8cae64eef650bac83d22a3d` added combined PostgreSQL listing-filter checks and a real Zot retag/pull-by-old-digest scenario. Hosted run [38874748475](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38874748475) passed Java 25 checks, lint, V1–V3 migration, stack startup, fail-closed self-check, live Docker acceptance, restore drill, SBOM generation, Trivy and cleanup. The acceptance log explicitly reports `Zot retained the previous manifest and image after its mutable tag was overwritten` and ends `Docker acceptance passed`. Catalog selection passed the newly added matrix on PostgreSQL, but the owner's separate confirmed repro is still open because its exact inputs are not encoded. The full CI job remains **failure** only because the pinned Core packaged-controller integration test still throws the known NPE at line 146. Artifact upload was skipped after that required failure.

## GitHub Actions run contents

The successful predecessor run 38827486941 reported:

- Gradle, Spotless, unit/architecture tests and JaCoCo: PASS.
- OpenAPI lint: PASS.
- Live Zot/OCI/catalog/plugin acceptance: PASS except the pinned Core packaged-controller test noted above.
- Isolated PostgreSQL/Zot/media backup and restore, immutable-manifest comparison, and OCI digest pull: PASS.
- SPDX SBOM generation and artifact upload: PASS.
- Trivy image scan: PASS under the time-limited upstream Zot/Tomcat CVE exceptions in `.trivyignore` and [Security](SECURITY.md).
- Ephemeral Compose teardown with volumes: PASS.

The predecessor run used `continue-on-error`, which is documented above as a historical baseline defect. The hardened workflow has a required live acceptance step without that suppression; run 38833700448 confirms the job remains red on the pinned Core failure. Restore, SBOM, Trivy and teardown use independent `always()`/cleanup conditions so diagnostics and cleanup continue after an acceptance failure.

## Compatibility boundary

Marketplace returns the original Core `PluginManifest v1` document or the v5.1 capability-envelope v2 document and does not implement a second plugin runtime. Core remains authoritative for a customer's actual Core version, trusted registry configuration, installed dependencies, configuration, installation state, and lifecycle. CI checks out the public Core repository at the pinned SHA in a separate workspace; no customer Core instance is required. See [Core compatibility](CORE_COMPATIBILITY.md) for the pinned contracts and documented template conflict.

## Development blockers versus production risks

Development verification is blocked on the full Core `validate → register → install` path against the exact Marketplace manifest, the PostgreSQL catalog ordering/filter defect reported by the owner, and the OpenSearch template conflict if that plugin is needed. A local HTTP Core run must trust `localhost:5000`, and the Docker daemon must allow that local registry as insecure; the test environment cannot assume a live Core is already running. The OpenSearch conflict is in the pinned Core validator and cannot be repaired in this repository without breaking compatibility.

Single-token supply-chain compromise, remote revocation of installed customer extensions, rate limiting behind a public proxy, public management/metrics exposure, and untrusted manifest markup are production security concerns. The Marketplace-only changes mitigate publisher-token compromise with independent approval and address the existing proxy/Actuator/input findings, but do not deliver image signatures or Core-side revocation enforcement. See [Security](SECURITY.md) and [post-implementation hardening](POST_IMPLEMENTATION_HARDENING.md).

The Marketplace advisory publisher now persists append-only support/security snapshots and exposes `/api/v1/marketplace/security/advisories`, signed with an Ed25519 key separate from the API credentials. `SignedAdvisoryFeedServiceTest` verifies the canonical snapshot signature against the generated public key and confirms the endpoint fails closed without signing configuration. The Docker acceptance script now checks the live HTTP signature against an independently configured public key and confirms a security revocation places every exact release image digest on the feed ban list; that new live gate awaits its first hosted run. This validates only Marketplace publication; Core verification, persistence and local containment are still required before the cross-repository security acceptance can pass.

The current development database is a single complete V1 schema snapshot. Historical CI records below describe the schema/migration checks that existed at those commits; the current CI validates only a clean database created from V1 and does not exercise upgrade migrations.

## Docker cleanup

CI uses a uniquely named acceptance Compose project and a cleanup step that runs `docker compose down -v`. The acceptance script's exit trap removes its uniquely named reference-plugin container, temporary image tags/digest references, and package directory. The restore drill is explicitly gated, uses only that disposable project, and deletes only its three named volumes. Neither workflow nor scripts call a global Docker prune. The local containers, project volumes, and test-only images created during this run were removed and checked. Desktop command review blocked deletion of the exact temporary credential directory created for local acceptance; it remains untracked and local. The repository's ignored `.local-marketplace` credentials also remain local and were not included in commits.
