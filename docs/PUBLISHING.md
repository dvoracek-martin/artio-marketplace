# Manual plugin publishing

## 1. Prepare and push OCI artifacts

Create a Zot publisher account named `publisher` in the bcrypt htpasswd file mounted at `/run/secrets/zot_htpasswd`. Use Docker or another OCI client to log in with that account's password and push images through the publisher endpoint. Resolve each artifact's digest from the public registry endpoint and use a reference at that public host in `manifest.json`. The manifest `image` and each `services[].image` must use exactly the configured `MARKETPLACE_REGISTRY_HOST`; tags and the publisher hostname are not accepted. Do not put credentials or arbitrary registry URLs in a manifest.

For local Compose, public pulls use `localhost:5000` and authenticated publishing uses `localhost:5001`. Remote publishers use a separate operator-configured publisher DNS hostname, such as `registry-push.example.com`; restrict that host at the reverse proxy/firewall to trusted operator networks. Both names route to the same Zot repository storage. The public hostname is what goes into plugin manifests and Artio Core's `ARTIO_EXTENSION_TRUSTED_REGISTRIES`. Local development uses HTTP and loopback only; configure the local Docker daemon to trust both localhost ports as insecure registries. Production must use HTTPS.

For a local build, log in interactively so Docker prompts for the Zot password; do not put a password in the command line. For a plugin with a Dockerfile in its source directory:

```powershell
docker login localhost:5001 --username publisher
docker build -t localhost:5001/public/plugins/stock-sync:1.0.0 .
docker push localhost:5001/public/plugins/stock-sync:1.0.0
$accept = 'application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json'
$response = Invoke-WebRequest -Method Head -Uri 'http://localhost:5000/v2/public/plugins/stock-sync/manifests/1.0.0' -Headers @{Accept=$accept}
$mainRef = "localhost:5000/public/plugins/stock-sync@$($response.Headers['Docker-Content-Digest'])"
```

Repeat those steps for each service image declared in the manifest, using a unique repository path and digest query. Check the returned reference ends in `@sha256:` followed by 64 lowercase hex characters. Put those immutable public references in the manifest. In a consumer Artio Core deployment, configure the public registry host in `ARTIO_EXTENSION_TRUSTED_REGISTRIES`; Core still runs its normal manifest and installation-time checks.

The runtime manifest is Artio Core's v1 Java contract copied into `marketplace-contract`. Required fields include `manifestVersion`, `id`, `name`, `vendor`, `version`, `description`, `minimumCoreVersion`, `image`, `dependencies`, `permissions`, `configuration`, `extensionPoints`, `healthPath`, and `diagnosticsPath`. Consult actual Core plugin examples and `PluginManifest.java` for optional fields. Do not publish Core template `REPLACE_WITH_PUBLISHED_IMAGE_DIGEST` placeholders.

## 2. Prepare listing metadata

Use `marketplace-contract/catalog-metadata-v1.schema.json` as the machine-readable schema. Metadata requires `schemaVersion: 1`, a `pluginId` matching the manifest `id`, stable lowercase `slug`, localized `title`, `tagline`, `description`, and `releaseNotes` with English present, `vendorDisplayName`, `author`, 1–5 supported categories, up to 20 tags, `pricingModel: FREE`, an icon path, and zero to ten screenshots. Locales are `en`, `de`, `fr`, and `it`. Optional external links must be absolute HTTPS URLs. Images are decoded and checked against format, dimensions, and upload limits before storage.

Example `catalog.json` (replace all values and asset paths):

```json
{
  "schemaVersion": 1,
  "pluginId": "com.example.stock-sync",
  "slug": "stock-sync",
  "title": {"en": "Stock Sync"},
  "tagline": {"en": "Keep inventory in sync"},
  "description": {"en": "Synchronizes product inventory with Example Stock."},
  "releaseNotes": {"en": "Initial release."},
  "vendorDisplayName": "Example Ltd",
  "author": "Example Team",
  "categories": ["catalog"],
  "tags": ["inventory", "sync"],
  "pricingModel": "FREE",
  "icon": "icon.png",
  "screenshots": []
}
```

## 3. Validate and stage a release

The included script calls the `/api/v1` publisher API, validates OCI images, uploads icon and screenshot files, and stages the complete release for review. It prints a `releaseId` and manifest digest. It does not publish the release.

PowerShell 7 (preview with `-WhatIf`; run without it to stage for review):

```powershell
$tokenPath = 'C:\secure\marketplace-publisher-token.txt'
.\scripts\publish-plugin.ps1 -Package 'C:\plugins\stock-sync' -MarketplaceUrl 'https://marketplace.example.com' -TokenFile $tokenPath
```

Bash / Linux / macOS:

```bash
export MARKETPLACE_PUBLISHER_TOKEN_FILE=/secure/marketplace-publisher-token.txt
./scripts/publish-plugin.sh --dry-run ./plugins/stock-sync https://marketplace.example.com
./scripts/publish-plugin.sh ./plugins/stock-sync https://marketplace.example.com
```

The token file contains the raw Marketplace publisher bearer token, not the Zot password. Protect it with OS file permissions and exclude it from shell history and source control. Dry run validates without creating a release or uploading media. Use a unique immutable version per plugin release. The script derives an idempotency key from plugin id, version, and manifest bytes; retrying identical content returns the same staged release. Content changes to a version already created are rejected; publish a new version instead.

## 4. Independently review and approve

An operator who does not hold the publisher token must inspect the exact staged manifest, catalog, and image digests through the admin-only review endpoint:

```bash
export MARKETPLACE_ADMIN_TOKEN_FILE=/secure/marketplace-admin-token.txt
./scripts/approve-release.sh RELEASE_ID https://marketplace.example.com "Reviewed OCI digests and catalog for release 1.0.0"
```

The approval records hashes of both the runtime manifest and catalog metadata. Any catalog edit clears approval and requires the operator to review it again. The publisher cannot approve; the admin token cannot publish. The configured publisher, admin, and revocation token hashes must be distinct, and the application refuses duplicate credentials.

PowerShell 7 can perform the same review and approval call:

```powershell
$base = 'https://marketplace.example.com'
$releaseId = 'RELEASE_ID'
$adminToken = (Get-Content -Raw 'C:\secure\marketplace-admin-token.txt').Trim()
$adminHeaders = @{ Authorization = "Bearer $adminToken" }
$draft = Invoke-RestMethod -Method Get -Uri "$base/api/v1/admin/marketplace/releases/$releaseId/review" -Headers $adminHeaders
# Inspect $draft.manifest, $draft.catalog, and $draft.manifestSha256 before approval.
$approval = @{ reason = 'Reviewed OCI digests and catalog for release 1.0.0' } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/marketplace/releases/$releaseId/approve" -Headers $adminHeaders -ContentType 'application/json' -Body $approval
$publisherToken = (Get-Content -Raw 'C:\secure\marketplace-publisher-token.txt').Trim()
Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/marketplace/releases/$releaseId/publish" -Headers @{ Authorization = "Bearer $publisherToken" }
```

After approval, the publisher completes publication with its separate credential:

```bash
export MARKETPLACE_PUBLISHER_TOKEN_FILE=/secure/marketplace-publisher-token.txt
./scripts/publish-staged-release.sh RELEASE_ID https://marketplace.example.com
```

This manual separation reduces the impact of compromise of either one API token. It is not a cryptographic signature/provenance check: the administrator must review the digest and trust the image provenance out of band. Artio Core does not verify signatures and is not changed by this approval flow.

## 5. Verify public availability

PowerShell 7:

```powershell
.\scripts\verify-published-plugin.ps1 -PluginId 'com.example.stock-sync' -Version '1.0.0' -MarketplaceUrl 'https://marketplace.example.com'
```

Bash:

```bash
./scripts/verify-published-plugin.sh com.example.stock-sync 1.0.0 https://marketplace.example.com
```

The verifier fetches the public release/manifest and checks that each digest-pinned image can be pulled. If the platform cannot pull from a local HTTP registry, configure registry trust or test through the production HTTPS hostname.

## 6. Manual API sequence

Publisher calls use `Authorization: Bearer <publisher-token>` and `/api/v1/admin/marketplace/...` paths. Call `POST /releases/validate`, then `POST /releases` with a stable `Idempotency-Key` (8–160 safe characters). Upload each file as `multipart/form-data` to `POST /releases/{releaseId}/assets?kind=icon|screenshot`; set returned `assetId` values in metadata and send the complete metadata to `PUT /releases/{releaseId}/catalog`. A separate admin uses `GET /releases/{releaseId}/review` to inspect the exact staged content and `POST /releases/{releaseId}/approve` with a review reason. The publisher then calls `POST /releases/{releaseId}/publish`. See [OpenAPI](../marketplace-contract/openapi.yaml) for details.

Publication is a two-person API workflow: a publisher token stages and publishes only after an admin token approves that exact manifest and catalog; neither token can perform both halves. Unlisting a single published release and plugin moderation (`ACTIVE`, `SUSPENDED`, `BANNED`) require the separate admin token. Revoking one release requires the separate revocation token and a reason/category (`SECURITY`, `MALWARE`, `POLICY`, `LEGAL`, `OTHER`). Revocation returns HTTP 410 for that release and removes it from recommendations; it does not notify Core, disable installed plugins, mutate other release states, or issue a remote kill command. Publication audit is available from `GET /api/v1/admin/marketplace/publication-audit` using the admin token. The sample reverse proxy restricts all admin routes to an operator network.

