# Local Marketplace admin console

The developer console is an Angular 22 application served by the Marketplace Java process. It is compiled into the **dev-only Docker image target** and mapped only while Spring profile `marketplace-local-dev` is active. It is not included in the production target. It is deliberately a local operator tool: direct loopback peer and Host validation, same-origin HTTP mutations, a session-bound CSRF token, no CORS, and no browser-held publisher/admin/Zot/signing credentials.

## Start and open it

On Windows, run `Start Marketplace.bat` in the repository root. It starts PostgreSQL, Marketplace, Zot and the registry gateway, then prints the selected addresses. Open:

```text
http://127.0.0.1:<Marketplace API port>/dev-admin/
```

The exact port is in `.local-marketplace/connection.txt`. The Zot UI is separately available at `http://127.0.0.1:<public registry port>`; it browses registry content and does not administer Marketplace listings. Artio Core is not required to be running. The local Marketplace may serve many separate Core installations; it has no per-Core connection, Docker socket, or customer credentials.

On macOS/Linux, provision the local-only secrets described in [deployment setup](DEPLOYMENT.md), then start with `deployment/compose.yaml` plus `deployment/compose.dev.yaml`. Set `MARKETPLACE_LOCAL_SIGNING_KEY_FILE` and `MARKETPLACE_LOCAL_IMPORTER_PASSWORD_FILE` to private local files. An isolated one-shot Compose helper copies them from read-only host mounts into the private `local_dev_secrets` volume as UID 10001, mode `0400`; the Marketplace container mounts that volume read-only. Imports persist separately in `marketplace_quarantine`. Do not mount these files in production.

## Upload and publish the first plugin

1. Build the plugin image locally, using the exact tag you will enter in the console. Create a Docker-save tar (plain, uncompressed tar):

   ```powershell
   docker build -t example/stock-sync:1.0.0 C:\src\stock-sync
   docker save --output C:\tmp\stock-sync-1.0.0.tar example/stock-sync:1.0.0
   ```

2. In **Nahrát image archive do Zot**, enter `com.example.stock-sync`, `1.0.0`, role `MAIN`, archive tag `example/stock-sync:1.0.0`, and select the tar. The server streams the bytes to quarantine, validates safe tar paths and file types without extraction, verifies OCI layer blobs, and calls Skopeo with `--all` to copy all platforms into private Zot staging. The returned `localhost:<port>/public/plugins/...@sha256:...` reference identifies the eventual public path and exact digest; it is not anonymously readable until an approved release is published. Copy it.

   For a sidecar, build and save its image, import with role `SIDECAR:<manifest service id>`, then copy that digest into the corresponding service `image` property.

3. Paste the **exact Core runtime manifest** into the runtime-manifest editor. Keep its real `id`, `version`, Core compatibility bounds, permissions/capabilities, dependencies, health/diagnostics paths and extension points. Replace only the image fields with the returned digest-pinned references. Do not add Marketplace-only manifest properties.

4. Paste `CatalogMetadataV1` into the catalog editor. The UI replaces `icon` with the uploaded icon's content-addressed ID, so a non-empty schema-shaped placeholder can be supplied there. Set `pluginId` to the manifest ID, and provide slug, localized title/tagline/description/release notes, vendor/author, categories, tags, `pricingModel: FREE`, and `screenshots: []`. Select the icon file below the JSON editors. For screenshots, add matching ordered entries to `screenshots` and select exactly the same number of files in the same order. Supported image formats and dimensions are validated server-side.

5. Click **Validovat**. The result uses Marketplace's existing Core manifest validators, closed capability allowlist, catalog schema validation, and live Zot digest verifier. Fix all errors before continuing. Choose **Uložit jako VALIDATED / čeká na schválení** to create the immutable release snapshot, upload icon/screenshots through the existing MediaStore and leave the release awaiting approval. Choose **Publikovat nyní — pouze lokální vývoj** only when you intentionally want the loopback-only local approval exception; the UI then invokes approval, promotion, descriptor signing and signature verification before reporting success. Select the plugin on the left to inspect releases.

6. For a release left awaiting approval, review the manifest, metadata, version and image digests. In local development, **Schválit lokálně** records an audit entry as `local-dev-ui` with the reason shown in the UI. **Publikovat** invokes the existing publish workflow: reservation, revalidation, digest-preserving Zot promotion, public digest verification, final publication, canonical descriptor generation, and Ed25519 signature verification using the persisted local development key. A failed signing step is returned as an error; it does not claim that the release is signed.

7. Verify the signed public descriptor from PowerShell (use the API port from `connection.txt`):

   ```powershell
   $api = 'http://127.0.0.1:18080'
   Invoke-RestMethod "$api/api/v1/marketplace/plugins/com.example.stock-sync/releases/1.0.0/descriptor"
   ```

   The endpoint returns only a published, signed release. A Core instance uses its normal Marketplace discovery, manifest validation, registration and install APIs. The Core Docker daemon must trust the exact public Zot registry host embedded in the immutable references as an insecure registry for local HTTP development; for the default stack this is the `localhost:<public-port>` from the generated connection file. This is a Docker daemon setting, not an API setting.

## Listing and lifecycle controls

- Published listing presentation is edited as a new immutable revision. Review the JSON, save a revision, and activate it locally with a reason. The revision CAS version prevents lost updates; rollback creates another revision and leaves history intact. Public catalog responses overlay approved presentation fields and keep release notes from the original release snapshot.
- Moderation states use the existing `ACTIVE`, `SUSPENDED`, and `BANNED` domain transitions. Unlisting affects one release. Revocation is terminal for that release and records the existing advisory/revocation effects.
- Draft deletion is allowed only before approval, signature, promotion, or advisory references. A plugin identity can be physically removed only when it has no releases, listing revisions, or advisory history. Published/revoked releases and Zot digests are never deleted through this GUI.

## Import limits and cleanup

The upload endpoint is raw streaming and requires a declared Content-Length. The maximum archive is 4 GiB, only one import runs at a time, metadata files are bounded, tar entry count and expanded sizes are bounded, and absolute/traversal paths, duplicate names, links, special files, compression, and invalid OCI blob digests are rejected. Skopeo runs as a process with a private temporary auth file; no credentials are returned to JavaScript or included in command arguments. The importer Zot identity can write only under `staging/**` in the local development Zot config. The server does not mount Docker's socket.

Quarantine is persistent to allow job inspection and manual recovery. A bounded hourly cleanup removes only archive files belonging to expired terminal jobs (24-hour expiry); Zot staging/public artifacts remain separate and are not garbage-collected by this job cleanup. Never use `docker system prune` to clean these resources. `Stop-Artio.cmd` / `scripts/stop-local-marketplace.ps1` stop owned containers and retain volumes. The explicitly confirmed `Stop-And-Prune-Artio.cmd` is destructive and deletes Marketplace local data as documented by that launcher.

## API routes

The local browser adapter uses versioned `/api/v1/dev-admin/**` routes for session bootstrap, plugins/releases, listing revisions, release workflow actions and imports. These routes do not exist without the dev profile. Archive creation is `POST /api/v1/dev-admin/imports`; raw bytes are sent with `PUT /api/v1/dev-admin/imports/{jobId}/archive`. Public Core routes remain under `/api/v1/marketplace/**`; publisher/admin APIs remain under `/api/v1/admin/marketplace/**`. See [OpenAPI](../marketplace-contract/openapi.yaml) for the existing public/publisher contracts and [ADR-0009](ADR-0009-local-dev-admin-and-oci-import.md) for the implementation boundary.

## Current verification boundaries

Unit tests cover tar safety, local request-origin/CSRF controls and public catalog revision overlay. The Docker-backed `T01–T18` checklist is maintained in [acceptance](ACCEPTANCE.md). Do not treat a Java/Angular build or archive-only live import as proof of the browser publication workflow, multi-platform/sidecar publication, local descriptor signing, Windows launchers, or Core install path; report those separately and run only in an isolated project with cleanup of test-owned containers, images, and volumes.
