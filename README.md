# Artio Marketplace

Artio Marketplace is a Java 25 / Spring Boot 4.1 service for publishing and discovering Artio Core plugins. It includes PostgreSQL metadata, a pinned Zot OCI registry, a versioned public REST API, and separate publisher, moderation, and revocation credentials. Releases require publisher staging and separate admin approval bound to the exact manifest and catalog before publication. The service validates the exact plugin manifest contract copied from Artio Core `911f8196b1d76113804e274009e07ada17f15b33`; it does not change or embed Core.

## Current scope

- Public catalog, search, plugin and release details, media delivery, compatible release selection, and publisher listing controls.
- Two-person manual release workflow for digest-pinned OCI images, localized metadata, screenshots, icon, and release notes.
- Publisher images enter private `staging/**`; after independent approval, Marketplace automatically promotes the complete main/sidecar OCI bundle to `public/**` and only then exposes the release in the catalog.
- One current PostgreSQL V1 schema snapshot and immutable release records; development does not maintain incremental schema migrations. OCI registry and uploaded media are persisted separately.
- Official releases expose a signed immutable descriptor only after complete Zot promotion and an independent Ed25519 signing workflow; custom manifest registration remains a separate Core path.
- Combined Marketplace + Zot container and PostgreSQL Compose deployment.
- Backoffice UI, payments, and licensing are intentionally out of scope.

See [architecture](docs/ARCHITECTURE.md), [Core compatibility](docs/CORE_COMPATIBILITY.md), the [acceptance evidence](docs/ACCEPTANCE.md), [OpenAPI](marketplace-contract/openapi.yaml), and the [publishing guide](docs/PUBLISHING.md).

Private staging and digest-preserving OCI promotion are verified against the pinned Zot registry, including a multi-platform index. Marketplace issues signed immutable release descriptors and a signed security/support advisory feed, and Core verifies official release descriptors. The live cross-repository acceptance also verifies Core advisory-feed ingestion, persistent exact-digest bans, and Docker containment. Image provenance beyond the descriptor signature and custom local archive installation remain open. A signed descriptor does not by itself prove image build provenance. See the [integration security roadmap](docs/ROADMAP.md) before treating a published plugin as fully verified.

## Run the whole Marketplace with Docker

You do **not** need Java, Gradle, or a separately running Artio Core. Install and start Docker Desktop with Compose v2. The stack has three containers: Marketplace (Java app plus Zot registry), PostgreSQL, and a small registry gateway. One click starts all three and keeps catalog data in Docker volumes.

### Windows: one click

Double-click [`Start Marketplace.bat`](Start%20Marketplace.bat) in the repository root. The first start generates separate local publisher/admin/revocation tokens, a Zot password, and a persistent development-only Ed25519 advisory signing key in the ignored `.local-marketplace` folder, selects available loopback ports, builds the Marketplace image, starts its dependencies, and waits for readiness. Later starts reuse the same credentials, signing identity, and data. The public advisory trust key is written to `.local-marketplace/advisory-trusted-key.txt`; the sibling Artio development launcher reads it automatically. Production deployments must provision their advisory public key out of band.

The script prints the local addresses. Defaults are API `http://127.0.0.1:18080`, public registry `localhost:15000`, and publisher registry `localhost:15001`; if any is busy, it selects another free port. The Zot UI is available at `http://127.0.0.1:<public-registry-port>` (for example, `http://127.0.0.1:15000`). The API, management, and registry ports bind to loopback only. Docker-internal ports are isolated inside this Compose network. This keeps the default ports away from common Artio services and avoids taking ports already in use. The selected ports and credentials are recorded in `.local-marketplace`.

The local Zot UI allows anonymous browsing of public registry repositories. To stage plugin images, sign in with username `publisher` and the password in `.local-marketplace/zot-password.txt`; that identity can write only under private `staging/**`. Approved releases are copied by Marketplace using a separate promoter credential stored in `.local-marketplace/zot-promotion-password.txt`. The one-click development launcher enables the UI through [`deployment/compose.dev.yaml`](deployment/compose.dev.yaml); the base Compose deployment keeps it disabled.

To stop the services while keeping data, run from the repository directory:

```powershell
docker compose --project-name artio-marketplace-local --env-file .local-marketplace/.env -f deployment/compose.yaml down
```

Run `Start Marketplace.bat` again to resume. Do not add `--volumes` unless you intentionally want to permanently delete Marketplace catalog data, Zot images, and uploaded media.

### macOS/Linux or manual Docker Compose

The development stack can be started with your own `.env` and secrets; see [deployment setup](docs/DEPLOYMENT.md) and [.env.example](.env.example). Include `-f deployment/compose.dev.yaml` to enable the Zot UI:

```sh
docker compose --env-file .env -f deployment/compose.yaml -f deployment/compose.dev.yaml up --build -d
```

The default host ports are `8080` (API), `8081` (management), `5000` (public registry and Zot UI), and `5001` (publisher registry), all bound to `127.0.0.1`. Override them with `MARKETPLACE_API_PORT`, `MARKETPLACE_MANAGEMENT_PORT`, `MARKETPLACE_REGISTRY_PORT`, and `MARKETPLACE_REGISTRY_PUBLISH_PORT` if another service already uses one. Without the development override, Zot UI remains disabled.

For local development, Zot serves HTTP. Docker clients that push/pull images must trust the selected registry endpoints as insecure registries. Configure this in Docker Desktop's **Settings → Docker Engine**, then apply/restart Docker Desktop. A host-run Core can use the printed public registry `localhost:<port>`. A Core running in Docker Desktop must use an address reachable from the host and Docker daemon: set `MARKETPLACE_REGISTRY_HOST=host.docker.internal:<public-registry-port>` in `.local-marketplace/.env` before staging releases, and set Artio `ARTIO_EXTENSION_TRUSTED_REGISTRIES` to the exact same host and port. Keep publisher pushes on the separate `localhost:<publisher-port>` endpoint; the public host is embedded in the immutable manifest image references. Add the `host.docker.internal:<public-registry-port>` endpoint to Docker Desktop's insecure-registry list for local HTTP. The one-click starter does not change Docker Engine or Core configuration.

For internet-facing deployment, do not expose these local Compose ports directly. Terminate HTTPS at a trusted reverse proxy/load balancer, use separate public and publisher registry hostnames, and restrict publisher/admin access as described in [the proxy example](deployment/reverse-proxy.example/marketplace.conf) and [deployment guide](docs/DEPLOYMENT.md).

## Publish a plugin

Marketplace itself has no graphical backoffice: plugin staging, approval, publication, and moderation use the versioned REST API and the included scripts. The public catalog is also API-only; see [OpenAPI](marketplace-contract/openapi.yaml). Zot provides a separate optional UI ([upstream project](https://github.com/project-zot/zui)) for browsing and managing OCI registry content. It is enabled for local development by [`deployment/zot/config.dev.json`](deployment/zot/config.dev.json) and disabled in the base/production configuration ([`deployment/zot/config.json`](deployment/zot/config.json)). The Zot UI does not publish or moderate Marketplace releases. Payments and licensing are not implemented.

After the independent admin approves and Marketplace finishes OCI promotion, run the protected **Sign published Marketplace release** GitHub workflow with its release UUID. The workflow signs Marketplace's exact canonical descriptor, then submits it using the isolated signature-ingest credential. Configure the protected `marketplace-signing` environment with the publisher token, signature-ingest token, signing key ID variable, Marketplace URL variable and Ed25519 private-key secret. Configure the matching public key in Marketplace as `MARKETPLACE_SIGNING_TRUSTED_KEYS` and in Core as `ARTIO_MARKETPLACE_TRUSTED_KEYS` (`key-id=base64url-X509-public-key`, comma-separated during rotation). The public `/api/v1/marketplace/plugins/{pluginId}/releases/{version}/descriptor` endpoint stays unavailable until this signature has been verified and stored. Keep the signing private key out of Marketplace runtime, Core, and ordinary publisher credentials.

1. Prepare a plugin package containing `manifest.json`, `catalog.json`, and the referenced icon/screenshots. Its runtime images (including sidecars) must be built and pushed to Zot first. The manifest must reference the public registry address with an immutable `@sha256:...` digest.
2. Push the image to the publisher registry. For a one-click local stack, configure the two `localhost` registry ports listed in `.local-marketplace/connection.txt` as insecure registries in Docker Desktop (**Settings → Docker Engine**; add them to any existing configuration and apply/restart). Then read the selected ports and push the image:

   ```powershell
   $settings = Get-Content '.local-marketplace/.env'
   $pushPort = [int](($settings | Where-Object { $_ -like 'MARKETPLACE_REGISTRY_PUBLISH_PORT=*' }) -split '=', 2)[1]
   $publicPort = [int](($settings | Where-Object { $_ -like 'MARKETPLACE_REGISTRY_PORT=*' }) -split '=', 2)[1]
   $image = "localhost:$pushPort/staging/plugins/my-plugin:1.0.0"
   docker login "localhost:$pushPort" --username publisher
   docker build -t $image 'C:\path\to\plugin-source'
   docker push $image
   $accept = 'application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json'
   $password = (Get-Content '.local-marketplace/zot-password.txt' -Raw).Trim()
   $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("publisher:$password"))
   $response = Invoke-WebRequest -Method Head -Uri "http://localhost:$pushPort/v2/staging/plugins/my-plugin/manifests/1.0.0" -Headers @{ Accept = $accept; Authorization = "Basic $auth" }
   $mainRef = "localhost:$publicPort/public/plugins/my-plugin@$($response.Headers['Docker-Content-Digest'])"
   Write-Host "Use this image in manifest.json: $mainRef"
   ```

   Put the printed immutable digest reference in `manifest.json`. Repeat for every sidecar image.
3. Run the staging script with the publisher token from `.local-marketplace/publisher-token.txt`:

   ```powershell
   $apiPort = [int](($settings | Where-Object { $_ -like 'MARKETPLACE_API_PORT=*' }) -split '=', 2)[1]
   .\scripts\publish-plugin.ps1 -Package 'C:\path\to\plugin-package' -MarketplaceUrl "http://127.0.0.1:$apiPort" -TokenFile '.local-marketplace\publisher-token.txt'
   ```

   Use the API port printed by the starter if it selected another one. The script validates the manifest, uploads catalog images, and stages a release; it does **not** publish it.
4. An operator reviews and approves the staged release using the separate admin token in `.local-marketplace/admin-token.txt`. The publisher then completes publication with its own token. Full copy/paste commands and digest lookup examples are in [the publishing guide](docs/PUBLISHING.md).

The local HTTP registry setup is for development only. Before publishing real plugins for other people to install, deploy behind HTTPS and review the [security notes](docs/SECURITY.md).

## Tests and builds

Run `./gradlew --no-daemon spotlessCheck clean build jacocoTestReport :marketplace-domain:jacocoTestCoverageVerification` with JDK 25. The Gradle toolchain is pinned to Java 25 and the wrapper to Gradle 9.8.1. Run the live Docker suite with `./scripts/docker-acceptance.sh` after starting the stack and exporting the publisher/admin/revocation token file paths, `MARKETPLACE_ZOT_TEST_PASSWORD`, and `MARKETPLACE_ZOT_PROMOTION_TEST_PASSWORD`. The isolated Zot htpasswd must contain both the publisher and promoter accounts. Set `ARTIO_CORE_PATH` to a read-only pinned Core checkout only when running isolated runtime/controller contract tests; no deployed or customer Core instance is required. Acceptance results and unrun limits are tracked in [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md).

## First manual publication

Prepare a plugin directory containing `manifest.json`, `catalog.json`, and the referenced icon/screenshot files. Every runtime image (including sidecars) must already be pushed to this Marketplace Zot registry with an immutable `@sha256:<64 lowercase hex>` reference. Use a Core-compatible manifest and catalog schema v1. Follow [the step-by-step publishing procedure](docs/PUBLISHING.md).

## Operations and security

Read [deployment](docs/DEPLOYMENT.md), [security](docs/SECURITY.md), [backup and restore](docs/BACKUP_RESTORE.md), and [operations](docs/OPERATIONS.md) before production rollout. The server exposes only versioned business APIs under `/api/v1/...`; Actuator health endpoints are operational endpoints, not Marketplace REST resources.
