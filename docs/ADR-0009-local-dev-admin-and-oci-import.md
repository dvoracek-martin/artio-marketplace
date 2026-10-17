# ADR-0009: Local development administration and OCI archive import

## Status

Accepted for the local-development feature. This ADR does not change Marketplace's public API or Artio Core's install lifecycle.

## Baseline reviewed on 2026-10-17

- Marketplace baseline: `47807f5feca86b9ee2b1963212fc9e79bbd5fba8` (`main`, clean, equal to `origin/main`).
- Artio Core baseline: `702f0282de74d3cf9101904d1de8222a08756aa9` (`main`, two local commits ahead of `origin/main`). At review time Core also had pre-existing, unrelated uncommitted edits in `OrderFulfillmentController.java` and `V1__initial_schema.sql`; this work must not stage, alter, or commit them.
- Existing developer Compose project `artio-marketplace-local` was running at review time with Marketplace API `127.0.0.1:18080`, management API `127.0.0.1:18081`, public Zot ingress `127.0.0.1:15000`, and publisher ingress `127.0.0.1:15001`. The `postgres_data`, `zot_data`, and `catalog_assets` volumes are persistent user data and are not acceptance-test resources.
- Marketplace currently has a single V1 development baseline migration. The V1 migration declares the release metadata and signed descriptor immutable after publication; new persisted features therefore require forward migrations and must preserve existing local records.
- Core consumes only versioned `/api/v1/marketplace/**` routes, verifies Ed25519 release descriptors against operator-configured keys, and registers/installs through its existing extension management and lifecycle controller. v1 and capability-envelope v2 manifests must retain their current Core validators.

## Decisions

1. **Dev UI boundary.** The Angular 22 UI is built as static assets and copied only into a development image target. A `marketplace-local-dev` Spring profile, set only by `deployment/compose.dev.yaml`, registers the no-login `/dev-admin/` and `/api/v1/dev-admin/**` handlers. These endpoints enforce local loopback Host/Origin rules, reject forwarded-host/client headers, use a session-bound CSRF token for mutations, and have no CORS policy. Production Compose does not activate the profile or contain the assets. Existing authenticated publisher, admin, revocation, signing-ingest APIs and Zot ACLs remain unchanged.

2. **Archive importer.** Browser uploads are raw streamed request bodies to a generated, job-scoped quarantine directory on a dedicated named volume; bytes are never stored in PostgreSQL or copied into heap buffers. A pinned Skopeo CLI invoked using `ProcessBuilder` argument arrays performs Docker archive and OCI archive transfers directly into Zot's `staging/**` namespace. Marketplace does not mount or use the Docker socket. A separate `oci-importer` registry identity has staging-only rights; its password is mounted read-only in the development overlay and is never returned to the browser or included in command arguments/logs. Public references are constructed server-side and use the actual digest read back from Zot. OCI indexes are copied with all child manifests and re-verified through the existing verifier.

3. **Inspection and bounds.** Archives are inspected without executing an image. A strict tar scanner rejects compressed/unknown inputs, unsafe or duplicate names, links, special files, malformed indexes/manifests, digest mismatches, excessive entry counts, archive size over the configured maximum, and expansion/resource limits. Skopeo receives only the server-generated archive path and registry destination. Upload/import jobs have durable identifiers, persisted progress, idempotency, one-import-at-a-time admission, a fixed disk budget and bounded cleanup by job ownership/TTL.

4. **Release workflow.** The UI calls shared release validation, approval, promotion, descriptor canonicalization and signature-verification operations; it does not update publication state to `PUBLISHED` directly and does not call Marketplace HTTP routes from itself. Local auto-approval is registered only in the local-dev profile, records actor `local-dev-ui` and a specific reason, and delegates to the same durable promotion operation. A local Ed25519 development signer reads the existing `.local-marketplace/release-signing-key.pem` read-only, verifies its public-key identity against the configured trust key, and submits the signature through the same descriptor validation path. Production signer secrets and CI signing credentials are not reused.

5. **Listing revisions and immutability.** Runtime manifests, signed descriptors, and release catalog snapshots remain immutable. Published presentation changes are stored as immutable, versioned listing revisions with compare-and-set activation, audit, history, and rollback. Their public catalog representation overlays only allowed presentation fields and always preserves the original release notes and immutable plugin identity. Normal publication uses separate admin approval; only the local-dev profile can activate through the audited local exception.

6. **Deletion.** Only never-published, never-approved, never-signed drafts without advisory/promotion references can be physically deleted. Published releases are unlisted; revocation remains terminal. Public Zot manifests/blobs are never deleted from this interface. Empty plugin identity deletion is guarded by referential checks. Ordinary stop retains volumes; the existing explicitly confirmed Artio prune remains the only coordinated destructive reset.

7. **Launcher integration.** Artio start/stop launchers share a PowerShell 5.1-compatible helper that reuses Marketplace's `artio-marketplace-local` project and `.local-marketplace` state. Core's local API URL uses the host-reachable `host.docker.internal` address; Docker daemon image pulls use the persisted public Zot host/port and the identical trusted-registry allowlist. Storefront remains an optional profile. Marketplace is stopped only by the explicit full-platform stop, never by Artio's pre-start restart operation.

## Consequences

- Database changes are forward-only migrations; the current developer stack is preserved and never used for destructive acceptance.
- The no-login capability is a local-workstation convenience, not a remotely deployable admin mode.
- OCI import adds a pinned parser/copier dependency and a dedicated staging-only Zot credential. Uploads consume bounded disk space and may take minutes; the UI reports actual byte/import progress and recoverable errors.
- A local signed release remains trusted only by Core instances explicitly configured with the existing local development trust key.
