# Acceptance evidence

This status distinguishes successful checks from failures and checks that have not run. A green job does not override a failed check inside a `continue-on-error` step.

## Pre-hardening baseline

Commit `7de67c23bd16f87687c1b6fc7ce530354fd534a7` was pushed to `main`. GitHub Actions run [38827486941](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38827486941) completed successfully. It verified Java 25.0.4, Gradle 9.8.1, formatting, unit/architecture tests, JaCoCo, OpenAPI lint, a live Compose/PostgreSQL/Zot stack, the Docker acceptance script, the backup/restore drill, SPDX SBOM generation, Trivy, and stack cleanup.

These results describe the predecessor implementation before the hardening work. At that baseline, the live acceptance shell script recorded a failure from one pinned Artio Core test and the workflow used `continue-on-error`. The hardening change removes that suppression; hosted CI for the new source must fail while this Core test remains unresolved.

## Post-hardening local candidate evidence

The source candidate is based on Marketplace hardening baseline `0f1d76e42908978911c7b10667827e033b38bc19`. Local verification on 2026-10-16 completed the Java 25 unit/architecture tests (including digest-verifier tests), Redocly OpenAPI lint, V1-to-V2 migration with pre-existing ambiguous rows, acceptance fail-closed self-check, full Compose/PostgreSQL/Zot Docker acceptance, PostgreSQL/Zot/media backup/restore with an exact manifest comparison and digest pull, and direct sensitive Actuator deny checks. The live acceptance output reached its final `Docker acceptance passed` line. Current detailed finding-by-finding status and residual risks are in [post-implementation hardening](POST_IMPLEMENTATION_HARDENING.md).

The exact combined local `spotlessCheck clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification` command failed in this Windows Docker environment when google-java-format threw `NoClassDefFoundError` for Guava nested classes during `spotlessJavaCheck`; separate clean-home `spotlessCheck` passed, and `clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification -x spotlessCheck` passed. Hosted CI passed the combined compile/format/unit/architecture/coverage step. No pinned Core test result is inferred from local Marketplace acceptance.

## Results

| Area | Status | Evidence |
|---|---|---|
| Java 25 compile, formatting, unit tests, architecture boundaries, JaCoCo | PASS | CI run 38825213413, `Compile, format, unit and architecture checks, coverage`; success on the source commit above. Local Java 25 container command `./gradlew --no-daemon clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification -x spotlessCheck` also succeeded. |
| Runtime dependency alignment | PASS | Java 25 Gradle dependency report resolves Jackson annotations `2.22`, Jackson 2 core/databind `2.21.7`, Jackson 3 core/databind `3.2.3`, and Tomcat `11.0.25`. Live app startup in CI passed after this change. |
| OpenAPI 3.1 contract | PASS | CI ran `npx --yes @redocly/cli@1.34.5 lint marketplace-contract/openapi.yaml`; successful. |
| Marketplace + PostgreSQL + Zot startup, health, and persistence | PASS | CI Compose stack became healthy; live checks persisted and reread the published releases after Marketplace restart. |
| Zot ACL and manual publication | PASS | Live acceptance denied anonymous writes, allowed anonymous reads, authenticated publisher pushes, validated four digest references across main/companion images, published `1.2.0` and `1.3.0`, and verified idempotent retries. |
| Public catalog, locales, release versions, manifest and media | PASS | Live `/api/v1/marketplace/...` checks covered localized listing/category reads and original release manifest retrieval; listing has uploaded icon and screenshot media. Catalog metadata remains separate from the Core manifest. |
| Runnable reference extension | PASS | Acceptance pulled and ran the published digest in a restricted container. Health/diagnostics and authenticated Core v1 invocation worked; missing token returned 401 and retry was deterministic. |
| Real OCI digest pull and multi-image/update/rollback runtime | PASS | The pinned Core `DockerExtensionRuntimeIntegrationTests` passed `installsInvokesUpdatesAndRollsBackRealRestrictedContainers` and `managesSidecarHealthAndPreservesDataAcrossBundleUpdateAndRollback` using the Marketplace-published image references. Anonymous Docker pull and denied push also passed. |
| Core catalog dependency/version check | PASS | Pinned Core `CatalogApiIntegrationTests.extensionLifecycleEnforcesDependencyStateAndVersionBeforeRuntimeCalls` passed. |
| Core packaged controller flow | FAIL | Pinned Core `PackagedExtensionControllerIntegrationTests.packagedControllerAuthenticatesAndManagesPrivateBundleWithoutCommerceServices` failed at `PackagedExtensionControllerIntegrationTests.java:146` with `NullPointerException` while dereferencing the health response. Core was not changed. The exact Core controller boundary therefore is not verified as passing. |
| Core egress integration test | NOT RUN | `DockerExtensionRuntimeIntegrationTests.restrictsRealPluginEgressToTheProviderGateway` was skipped by its test fixture. |
| Offline-after-install | PARTIAL | CI stopped the Marketplace service while the independently running reference extension remained healthy and returned the same authenticated invocation result. A full Core Plugin Manager offline install/update failure-diagnostic path was not run. |
| Backup/restore drill | PASS | CI run 38827486941 backed up PostgreSQL, Zot and media after a controlled Marketplace restart; destroyed only its unique acceptance project's volumes; restored fresh volumes; confirmed the published manifest was byte-for-byte identical and pulled its image by digest. |
| Full Core Admin Plugin Manager install/register/enable/uninstall using the exact published manifest | NOT RUN | The isolated pinned Core runtime and catalog tests cover real Docker lifecycle and catalog policy, but the full admin-controller flow did not pass. No deployed customer Core is assumed or contacted. |
| Local Windows end-to-end Docker acceptance | NOT RUN for the latest outage/restore script | Previous local OCI acceptance evidence predates the added outage scenario; the current live acceptance is run in isolated GitHub CI. |
| Scaling to thousands of independent Core installations | NOT RUN | Architecture has no per-customer Core connections or Docker socket, but no representative load profile or throughput test has been performed. |

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

Marketplace returns the original Core `PluginManifest v1` document and does not implement a second plugin runtime. Core remains authoritative for a customer's actual Core version, trusted registry configuration, installed dependencies, configuration, installation state, and lifecycle. CI checks out the public Core repository at the pinned SHA in a separate workspace; no customer Core instance is required. See [Core compatibility](CORE_COMPATIBILITY.md) for the pinned contract and documented manifest/template conflict.

## Docker cleanup

CI uses a uniquely named acceptance Compose project and a cleanup step that runs `docker compose down -v`. The acceptance script's exit trap removes its uniquely named reference-plugin container, temporary image tags/digest references, and package directory. The restore drill is explicitly gated, uses only that disposable project, and deletes only its three named volumes. Neither workflow nor scripts call a global Docker prune. The local Docker containers, project volumes, and test-only images created during this work were removed. The Windows execution policy blocked deletion of the ignored local `.env` and `secrets/` acceptance credentials; those files were not tracked or pushed and remain a local cleanup item.
