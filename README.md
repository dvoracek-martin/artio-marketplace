# Artio Marketplace

Artio Marketplace is a Java 25 / Spring Boot 4.1 service for publishing and discovering Artio Core plugins. It includes PostgreSQL metadata, a pinned Zot OCI registry, a versioned public REST API, and a token-protected publisher API. The service validates the exact plugin manifest contract copied from Artio Core `911f8196b1d76113804e274009e07ada17f15b33`; it does not change or embed Core.

## Current scope

- Public catalog, search, plugin and release details, media delivery, compatible release selection, and publisher listing controls.
- Manual publisher workflow for digest-pinned OCI images, localized metadata, screenshots, icon, and release notes.
- PostgreSQL migrations and immutable release records; OCI registry and uploaded media are persisted separately.
- Combined Marketplace + Zot container and PostgreSQL Compose deployment.
- Backoffice UI, payments, and licensing are intentionally out of scope.

See [architecture](docs/ARCHITECTURE.md), [Core compatibility](docs/CORE_COMPATIBILITY.md), the [acceptance evidence](docs/ACCEPTANCE.md), [OpenAPI](marketplace-contract/openapi.yaml), and the [publishing guide](docs/PUBLISHING.md).

## Run locally with Docker

Prerequisites: Docker Engine with Compose v2. Build requires Internet access to Maven Central and the pinned base images.

1. Create `.env` from `.env.example`. Replace the PostgreSQL password with a unique secret. Create a random publisher bearer token of at least 32 characters and set `MARKETPLACE_PUBLISHER_TOKEN_SHA256` to its lowercase SHA-256 hex digest. Keep the raw token in a separate protected file for the publishing script. The API stores only its hash.
2. Create a bcrypt htpasswd file with a user named `publisher`; place it outside this repository and set `ZOT_HTPASSWD_FILE` to its absolute path. With Docker and Apache `htpasswd`, run `docker run --rm -it httpd:2.4-alpine htpasswd -B -C 12 publisher`, enter a separate Zot password, and save the resulting single `publisher:$2y$...` line to that file. Restrict file access.
3. Start the stack: `docker compose --env-file .env -f deployment/compose.yaml up --build -d`.
4. Wait for `docker compose --env-file .env -f deployment/compose.yaml ps` to show Marketplace, PostgreSQL, and the small registry gateway healthy. Check `http://localhost:8080/actuator/health/readiness` and `http://localhost:5000/v2/`.

The Marketplace image contains Java and Zot. PostgreSQL is separate. Nginx exposes two loopback-only registry listeners to that same Zot storage: public pulls through `localhost:5000` get an anonymous `200` ping, while publisher login/push goes through `localhost:5001` and keeps Zot's Basic-auth challenge. This split is needed for Docker's challenge behavior. Manifest digests must use only the public host (`MARKETPLACE_REGISTRY_HOST=localhost:5000`); publisher tooling pushes through `MARKETPLACE_REGISTRY_PUBLISH_HOST=localhost:5001`. Zot still enforces anonymous read-only and authenticated publisher writes. For public deployment, terminate HTTPS at a trusted reverse proxy/load balancer and configure the public and publisher hostnames as shown in [the proxy example](deployment/reverse-proxy.example/marketplace.conf); restrict publisher ingress to trusted operator networks. Do not expose this local-only Compose configuration directly to the Internet.

## Tests and builds

Run `./gradlew --no-daemon spotlessCheck clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification` with JDK 25. The Gradle toolchain is pinned to Java 25 and the wrapper to Gradle 9.8.1. Run the live Docker suite with `./scripts/docker-acceptance.sh` after starting the stack and exporting `MARKETPLACE_PUBLISHER_TOKEN_FILE` and `MARKETPLACE_ZOT_TEST_PASSWORD`. Set `ARTIO_CORE_PATH` to a read-only pinned Core checkout to run isolated runtime/controller contract tests; no deployed or customer Core instance is required. Acceptance results and unrun limits are tracked in [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md).

## First manual publication

Prepare a plugin directory containing `manifest.json`, `catalog.json`, and the referenced icon/screenshot files. Every runtime image (including sidecars) must already be pushed to this Marketplace Zot registry with an immutable `@sha256:<64 lowercase hex>` reference. Use a Core-compatible manifest and catalog schema v1. Follow [the step-by-step publishing procedure](docs/PUBLISHING.md).

## Operations and security

Read [deployment](docs/DEPLOYMENT.md), [security](docs/SECURITY.md), [backup and restore](docs/BACKUP_RESTORE.md), and [operations](docs/OPERATIONS.md) before production rollout. The server exposes only versioned business APIs under `/api/v1/...`; Actuator health endpoints are operational endpoints, not Marketplace REST resources.
