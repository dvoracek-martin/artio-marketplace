# Acceptance evidence

This status distinguishes successful checks from failures and checks that have not run. A green job does not override a failed check inside a `continue-on-error` step.

## Latest verified source commit

Commit `a057638cf26dc70a2d60ca2b25b68ff9eac5a3bf` was pushed to `main`. GitHub Actions run [38825213413](https://github.com/dvoracek-martin/artio-marketplace/actions/runs/38825213413) completed successfully. It verified Java 25.0.4, Gradle 9.8.1, formatting, unit/architecture tests, JaCoCo, OpenAPI lint, a live Compose/PostgreSQL/Zot stack, the Docker acceptance script, SPDX SBOM generation, Trivy, and stack cleanup.

The live acceptance shell script exited 1 only after recording a failure from one pinned Artio Core test; the workflow intentionally continues to SBOM and scans. The hosted GitHub Actions run is green, but the Core controller check itself is not green.

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
| Backup/restore drill | NOT RUN | Run 38826852486 invoked the script successfully but its first manifest read returned HTTP 429 because the preceding rate-limit acceptance had exhausted the public API quota. The failure happened before backup capture or volume deletion; CI cleanup removed only ephemeral acceptance volumes. The drill now restarts only its disposable Marketplace service to reset that test quota before probing; wait for another CI run before treating restore as verified. |
| Full Core Admin Plugin Manager install/register/enable/uninstall using the exact published manifest | NOT RUN | The isolated pinned Core runtime and catalog tests cover real Docker lifecycle and catalog policy, but the full admin-controller flow did not pass. No deployed customer Core is assumed or contacted. |
| Local Windows end-to-end Docker acceptance | NOT RUN for the latest outage/restore script | Previous local OCI acceptance evidence predates the added outage scenario; the current live acceptance is run in isolated GitHub CI. |
| Scaling to thousands of independent Core installations | NOT RUN | Architecture has no per-customer Core connections or Docker socket, but no representative load profile or throughput test has been performed. |

## GitHub Actions run contents

The successful run reported:

- Gradle, Spotless, unit/architecture tests and JaCoCo: PASS.
- OpenAPI lint: PASS.
- Live Zot/OCI/catalog/plugin acceptance: PASS except the pinned Core packaged-controller test noted above.
- SPDX SBOM generation and artifact upload: PASS.
- Trivy image scan: PASS under the time-limited upstream Zot/Tomcat CVE exceptions in `.trivyignore` and [Security](SECURITY.md).
- Ephemeral Compose teardown with volumes: PASS.

The acceptance step uses `continue-on-error` so an upstream pinned Core integration failure does not suppress SBOM generation, vulnerability scanning, or cleanup. Review the individual test result above in addition to the overall job conclusion.

## Compatibility boundary

Marketplace returns the original Core `PluginManifest v1` document and does not implement a second plugin runtime. Core remains authoritative for a customer's actual Core version, trusted registry configuration, installed dependencies, configuration, installation state, and lifecycle. CI checks out the public Core repository at the pinned SHA in a separate workspace; no customer Core instance is required. See [Core compatibility](CORE_COMPATIBILITY.md) for the pinned contract and documented manifest/template conflict.

## Docker cleanup

CI uses a uniquely named acceptance Compose project and a cleanup step that runs `docker compose down -v`. The acceptance script's exit trap removes its uniquely named reference-plugin container, temporary image tags/digest references, and package directory. The restore drill is explicitly gated, uses only that disposable project, and deletes only its three named volumes. Neither workflow nor scripts call a global Docker prune. Local acceptance credentials, temporary package files, project stack volumes and test-only images were removed after the earlier local run.
