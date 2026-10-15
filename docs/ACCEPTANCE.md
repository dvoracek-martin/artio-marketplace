# Acceptance evidence

This status records work executed against this checkout. A green unit suite or a runnable CI step is not runtime evidence for a test that has not completed.

## Local results

| Area | Status | Evidence |
|---|---|---|
| Java 25 compile, formatting, unit tests, architecture boundary, JaCoCo | PASS | `./gradlew --no-daemon spotlessApply clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification` in the pinned Eclipse Temurin 25 JDK image; 46 tasks, `BUILD SUCCESSFUL`. Includes Core manifest fixtures and rate-limit unit tests. |
| OpenAPI 3.1 lint | PASS | `npx --yes @redocly/cli@1.34.5 lint marketplace-contract/openapi.yaml`; license metadata is explicit. |
| Live PostgreSQL + Marketplace + Zot startup and health | PASS | `docker compose --env-file .env -f deployment/compose.yaml up --build -d`; all services became healthy and Flyway applied V1. |
| OCI ACL and manual publication | PASS | `scripts/docker-acceptance.sh`: anonymous read/pull, anonymous write denied, publisher-authenticated pushes, four main/companion digest references, two releases, dry-run validation, successful publication and idempotent retry. |
| Public catalog and Core manifest | PASS | Live localized listing/categories and release manifests verified through `/api/v1/marketplace/...`; metadata remains separate from the Core manifest. |
| Real reference plugin | PASS | Restricted container ran the published v1.2.0 digest. Health and diagnostics succeeded; missing token returned 401; Core v1 authenticated invocation returned a response; retry was deterministic. |
| Restart persistence | PASS | Acceptance restarted the Marketplace container, reread both release manifests, removed test-local image tags, and pulled all four artifacts again from Zot by digest. |
| API rate limiting | PASS | Unit test covered public/publisher separation and trusted-proxy handling. Live burst returned HTTP 429 with `Retry-After` and `RATE_LIMITED`. |
| Core Docker runtime / packaged controller lifecycle | NOT RUN LOCALLY | Acceptance can run pinned Core `DockerExtensionRuntimeIntegrationTests` and `PackagedExtensionControllerIntegrationTests` when `ARTIO_CORE_PATH` is set. Local host has JDK 24, not required JDK 25; user-scope Winget had no applicable installer. CI now checks out the pinned public Core commit and invokes those tests with Marketplace-published image references. CI completion is not yet observed at this commit. |
| Full Core admin API install using the exact Marketplace manifest; offline-after-install | NOT RUN | Needs a booted isolated Core application with administrator setup, Docker runtime configuration, and its required secret material. This repository does not modify or launch a customer Core instance. The existing runtime tests remain separate evidence and are not represented as the full Plugin Manager flow. |
| Backup/restore drill and local vulnerability/SBOM scans | NOT RUN LOCALLY | Procedures and pinned CI scan/SBOM jobs are present; a local restore rehearsal and completed remote CI run are not yet recorded. |

## Docker cleanup

The acceptance script uses an EXIT trap to remove its uniquely named reference-plugin container, local main/companion tag and digest references, any test-only Python base image absent before the run, and its temporary package directory. The final local cleanup also stops the Compose services and removes only this project's named PostgreSQL, Zot and catalog-media volumes with `docker compose ... down -v`; it does not run a global Docker prune.

## Compatibility boundary

The tests prove the pinned manifest contract and the executable extension protocol. Core remains authoritative for the customer's actual Core version, trusted registry configuration, installed dependencies, configuration, installation state, and lifecycle. See [Core compatibility](CORE_COMPATIBILITY.md) for the pinned commit, actual versioned routes, and the specific OpenSearch template conflict.
