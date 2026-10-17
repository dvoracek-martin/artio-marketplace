#!/usr/bin/env bash
set -euo pipefail

api_base="${MARKETPLACE_DEV_API_BASE:-http://127.0.0.1:18080}"
management="${MARKETPLACE_DEV_MANAGEMENT_URL:-http://127.0.0.1:18081/actuator/health/readiness}"
registry_port="${MARKETPLACE_DEV_REGISTRY_PORT:-15000}"
importer_password_file="${MARKETPLACE_LOCAL_IMPORTER_PASSWORD_FILE:-secrets/zot-importer-password.txt}"
tmp=$(mktemp -d)
image="artio/dev-admin-archive-fixture:accept-$(openssl rand -hex 8)"
base_image='python:3.12-alpine'
base_was_present=0
docker image inspect "$base_image" >/dev/null 2>&1 && base_was_present=1
cleanup() {
  docker image rm "$image" >/dev/null 2>&1 || true
  if [[ "$base_was_present" == 0 ]]; then docker image rm "$base_image" >/dev/null 2>&1 || true; fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT

for _ in $(seq 1 120); do
  if curl --silent --fail "$management" | jq -e '.status == "UP"' >/dev/null 2>&1; then break; fi
  sleep 2
done
curl --silent --fail "$management" | jq -e '.status == "UP"' >/dev/null
echo 'Dev Marketplace readiness passed.'

ui=$(curl --silent --show-error --fail "$api_base/dev-admin/")
grep -q '<ama-root>' <<<"$ui"
echo 'Development console route passed.'
mkdir -p "$tmp/cookies"
session=$(curl --silent --show-error --fail -c "$tmp/cookies/session.txt" "$api_base/api/v1/dev-admin/session")
jq -e 'keys == ["apiVersion","csrfToken"] and (.csrfToken|length > 20)' <<<"$session" >/dev/null
csrf=$(jq -r .csrfToken <<<"$session")
echo 'Session bootstrap passed.'

# A same-origin POST without the session CSRF token must be rejected.
csrf_status=$(curl --silent --show-error -o "$tmp/csrf-response.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H 'Content-Type: application/json' \
  -X POST -d '{}' "$api_base/api/v1/dev-admin/imports")
[[ "$csrf_status" == 403 ]]
echo 'CSRF rejection passed.'

docker build --quiet --build-arg ARTIO_REFERENCE_VERSION=1.0.0 \
  --tag "$image" examples/reference-plugin >/dev/null
docker save --output "$tmp/fixture.tar" "$image"
echo "Docker-save fixture created ($(stat -c '%s' "$tmp/fixture.tar") bytes)."

job_payload=$(jq -cn --arg image "$image" \
  '{pluginId:"com.example.dev-import",version:"1.0.0",role:"MAIN",archiveTag:$image}')
job=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-$(openssl rand -hex 12)" \
  --data-binary "$job_payload" \
  "$api_base/api/v1/dev-admin/imports")
job_id=$(jq -r .jobId <<<"$job")
if [[ ! "$job_id" =~ ^[0-9a-f-]{36}$ ]]; then
  echo "Could not create import job: ${job:-no response}" >&2
  exit 1
fi
echo 'Import job creation passed.'
upload_status=$(curl --silent --show-error -o "$tmp/upload.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -H 'Content-Type: application/octet-stream' -H 'X-Archive-Name: fixture.tar' \
  -H "X-Archive-Image: $image" \
  -X PUT --data-binary "@$tmp/fixture.tar" "$api_base/api/v1/dev-admin/imports/$job_id/archive")
if [[ "$upload_status" != 202 ]]; then
  echo "Archive upload returned HTTP $upload_status: $(cat "$tmp/upload.json")" >&2
  exit 1
fi
echo 'Streaming archive upload returned HTTP 202.'

state=''
for _ in $(seq 1 300); do
  result=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" "$api_base/api/v1/dev-admin/imports/$job_id")
  state=$(jq -r .state <<<"$result")
  [[ "$state" == STAGED || "$state" == FAILED ]] && break
  sleep 2
done
if [[ "$state" != STAGED ]]; then
  echo "OCI archive import did not stage successfully: ${result:-no response}" >&2
  exit 1
fi
reference=$(jq -r .image <<<"$result")
[[ "$reference" =~ @sha256:[a-f0-9]{64}$ ]]
digest=${reference##*@}
staging_tag="import-${job_id//-/}"
password=$(<"$importer_password_file")
curl --silent --show-error --fail --head \
  -u "oci-importer:$password" \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json' \
  "http://127.0.0.1:$registry_port/v2/staging/plugins/com.example.dev-import/1.0.0/main/manifests/$staging_tag" \
  | grep -qi "Docker-Content-Digest: $digest"
public_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' --head \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.dev-import/1.0.0/main/manifests/$digest")
[[ "$public_status" != 200 ]]

# A bundle role uses the Core PluginManifest v1 BundleService service ID. Keep
# the main and sidecar artifacts separately digest-pinned through publication.
sidecar_payload=$(jq -cn --arg image "$image" \
  '{pluginId:"com.example.dev-import",version:"1.0.0",role:"SIDECAR:companion",archiveTag:$image}')
sidecar_job=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-sidecar-$(openssl rand -hex 12)" \
  --data-binary "$sidecar_payload" "$api_base/api/v1/dev-admin/imports")
sidecar_job_id=$(jq -r .jobId <<<"$sidecar_job")
[[ "$sidecar_job_id" =~ ^[0-9a-f-]{36}$ ]]
sidecar_upload_status=$(curl --silent --show-error -o "$tmp/sidecar-upload.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -H 'Content-Type: application/octet-stream' -H 'X-Archive-Name: fixture.tar' \
  -H "X-Archive-Image: $image" -X PUT --data-binary "@$tmp/fixture.tar" \
  "$api_base/api/v1/dev-admin/imports/$sidecar_job_id/archive")
[[ "$sidecar_upload_status" == 202 ]] || {
  echo "Sidecar archive upload returned HTTP $sidecar_upload_status: $(cat "$tmp/sidecar-upload.json")" >&2
  exit 1
}
sidecar_state=''
for _ in $(seq 1 300); do
  sidecar_result=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
    "$api_base/api/v1/dev-admin/imports/$sidecar_job_id")
  sidecar_state=$(jq -r .state <<<"$sidecar_result")
  [[ "$sidecar_state" == STAGED || "$sidecar_state" == FAILED ]] && break
  sleep 2
done
[[ "$sidecar_state" == STAGED ]] || {
  echo "OCI sidecar archive import did not stage successfully: ${sidecar_result:-no response}" >&2
  exit 1
}
sidecar_reference=$(jq -r .image <<<"$sidecar_result")
[[ "$sidecar_reference" =~ @sha256:[a-f0-9]{64}$ ]]
sidecar_digest=${sidecar_reference##*@}
curl --silent --show-error --fail --head \
  -u "oci-importer:$password" \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json' \
  "http://127.0.0.1:$registry_port/v2/staging/plugins/com.example.dev-import/1.0.0/sidecar-companion/manifests/import-${sidecar_job_id//-/}" \
  | grep -qi "Docker-Content-Digest: $sidecar_digest"
echo 'Main and companion sidecar images were independently staged with verified digests.'

# Drive the same versioned browser adapter used by the local UI through release
# validation, immutable draft creation, media upload, approval, promotion and
# persisted Ed25519 signing. The fixture image is intentionally only a registry
# artifact; Core runtime behavior is covered by the separate pinned-Core gate.
cat > "$tmp/manifest.json" <<JSON
{
  "manifestVersion":"1",
  "id":"com.example.dev-import",
  "name":"Artio Dev Import Acceptance",
  "vendor":"Artio Marketplace",
  "version":"1.0.0",
  "description":"End-to-end local admin publication acceptance fixture.",
  "minimumCoreVersion":"0.1.0",
  "maximumCoreVersion":"99.0.0",
  "image":"$reference",
  "dependencies":[],
  "permissions":["payment.process"],
  "configuration":{},
  "extensionPoints":["payment.provider.v1"],
  "healthPath":"/health",
  "diagnosticsPath":"/diagnostics",
  "geographicAvailability":{"scope":"GLOBAL","countries":[]},
  "persistentPaths":[],
  "services":[{"id":"companion","image":"$sidecar_reference","port":8080,"healthPath":"/health","configurationBindings":{},"dependsOn":[],"persistentPaths":[],"internalPorts":[],"memoryLimitMb":64}]
}
JSON
placeholder="sha256-$(printf '0%.0s' {1..64})"
cat > "$tmp/catalog.json" <<JSON
{
  "schemaVersion":1,
  "pluginId":"com.example.dev-import",
  "slug":"dev-import-acceptance",
  "title":{"en":"Dev Import Acceptance"},
  "tagline":{"en":"Local publication acceptance fixture"},
  "description":{"en":"Verifies the local admin import and signed publication flow."},
  "releaseNotes":{"en":"Acceptance fixture release 1.0.0."},
  "vendorDisplayName":"Artio Marketplace",
  "author":"Artio Marketplace engineering",
  "categories":["other"],
  "tags":["acceptance","local-dev"],
  "pricingModel":"FREE",
  "icon":"$placeholder",
  "screenshots":[{"assetId":"$placeholder","order":0,"alt":{"en":"Runnable Artio reference plugin"},"caption":{"en":"Imported OCI image publication acceptance"}}]
}
JSON
jq -n --slurpfile manifest "$tmp/manifest.json" --slurpfile catalog "$tmp/catalog.json" \
  '{manifest:$manifest[0],catalog:$catalog[0]}' > "$tmp/submission.json"
validation=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  --data-binary "@$tmp/submission.json" "$api_base/api/v1/dev-admin/releases/validate")
jq -e '.valid == true and (.errors | length == 0)' <<<"$validation" >/dev/null || {
  echo "Dev release validation failed: $validation" >&2
  exit 1
}
echo 'Local release validation passed.'

draft=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-release-$(openssl rand -hex 12)" \
  --data-binary "@$tmp/submission.json" "$api_base/api/v1/dev-admin/releases")
release_id=$(jq -r .releaseId <<<"$draft")
[[ "$release_id" =~ ^[0-9a-f-]{36}$ ]] || {
  echo "Could not create dev release draft: $draft" >&2
  exit 1
}
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDvkAAAAASUVORK5CYII=' \
  | base64 -d > "$tmp/icon.png"
asset=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -F "kind=icon" -F "file=@$tmp/icon.png;type=image/png" \
  "$api_base/api/v1/dev-admin/releases/$release_id/assets")
asset_id=$(jq -r .assetId <<<"$asset")
[[ "$asset_id" =~ ^sha256-[a-f0-9]{64}$ ]]
shot=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -F "kind=screenshot" -F "file=@$tmp/icon.png;type=image/png" \
  "$api_base/api/v1/dev-admin/releases/$release_id/assets")
shot_id=$(jq -r .assetId <<<"$shot")
[[ "$shot_id" =~ ^sha256-[a-f0-9]{64}$ ]]
jq --arg icon "$asset_id" --arg shot "$shot_id" \
  '.icon=$icon | .screenshots[0].assetId=$shot' \
  "$tmp/catalog.json" > "$tmp/catalog-with-asset.json"
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -X PUT --data-binary "@$tmp/catalog-with-asset.json" \
  "$api_base/api/v1/dev-admin/releases/$release_id/catalog" >/dev/null
echo 'Draft and catalog media upload passed.'

# Restart only the uniquely named acceptance Marketplace service. Session state
# is intentionally re-created, while the draft, media, import job and Zot bytes
# must survive in their configured persistent stores.
docker compose --project-name "${MARKETPLACE_DEV_PROJECT:?}" \
  --env-file "${MARKETPLACE_DEV_ENV_FILE:?}" \
  -f deployment/compose.yaml -f deployment/compose.dev.yaml restart marketplace >/dev/null
for _ in $(seq 1 120); do
  if curl --silent --fail "$management" | jq -e '.status == "UP"' >/dev/null 2>&1; then break; fi
  sleep 2
done
curl --silent --show-error --fail "$management" | jq -e '.status == "UP"' >/dev/null
: > "$tmp/cookies/session.txt"
session=$(curl --silent --show-error --fail -c "$tmp/cookies/session.txt" "$api_base/api/v1/dev-admin/session")
csrf=$(jq -r .csrfToken <<<"$session")
job_after_restart=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  "$api_base/api/v1/dev-admin/imports/$job_id")
release_after_restart=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  "$api_base/api/v1/dev-admin/releases/$release_id")
jq -e '.state == "STAGED"' <<<"$job_after_restart" >/dev/null
jq -e '.state == "VALIDATED" and .approvalState == "PENDING"' \
  <<<"$release_after_restart" >/dev/null
for route in \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import" \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import/releases/1.0.0/descriptor"; do
  hidden_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' "$route")
  [[ "$hidden_status" == 404 ]]
done
echo 'Import, unapproved release draft, and quarantine/Zot media state persisted over Marketplace restart; public routes stayed hidden.'

curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -d '{"reason":"Isolated local admin acceptance review"}' \
  "$api_base/api/v1/dev-admin/releases/$release_id/approve" >/dev/null
published=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -X POST \
  "$api_base/api/v1/dev-admin/releases/$release_id/publish")
jq -e '.state == "PUBLISHED" and .signatureState == "SIGNED"' <<<"$published" >/dev/null || {
  echo "Dev release was not published and signed: $published" >&2
  exit 1
}
descriptor=$(curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import/releases/1.0.0/descriptor")
jq -e '.signature.algorithm == "Ed25519" and .descriptor.version == "1.0.0"' \
  <<<"$descriptor" >/dev/null
printf '%s' "$descriptor" | jq -cS '{descriptor,signature}' > "$tmp/descriptor.before.json"
listing=$(curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import?locale=en")
published_icon=$(jq -r '.metadata.icon' <<<"$listing")
published_screenshot=$(jq -r '.metadata.screenshots[0].assetId' <<<"$listing")
[[ "$published_icon" == "$asset_id" && "$published_screenshot" == "$shot_id" ]]
for asset_id_to_check in "$published_icon" "$published_screenshot"; do
  curl --silent --show-error --fail "$api_base/api/v1/marketplace/assets/$asset_id_to_check" \
    -o "$tmp/$asset_id_to_check.png"
  [[ -s "$tmp/$asset_id_to_check.png" ]]
done

# Published presentation changes use append-only listing revisions. Exercise
# activation, stale-version conflict, history and rollback while retaining the
# original release descriptor and runtime manifest.
effective=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/effective-listing")
listing_version=$(jq -r .listingRevisionVersion <<<"$effective")
original_metadata=$(jq -c .metadata <<<"$effective")
original_title=$(jq -r '.metadata.title.en' <<<"$effective")
baseline_revision=$(jq -cn --argjson metadata "$original_metadata" --argjson expected "$listing_version" \
  '{metadata:$metadata,expectedListingVersion:$expected}')
baseline_result=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  --data-binary "$baseline_revision" \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/revisions")
baseline_id=$(jq -r .revisionId <<<"$baseline_result")
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -d '{"reason":"Acceptance baseline presentation"}' \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/revisions/$baseline_id/activate" >/dev/null
candidate=$(jq '.title.en="Temporary acceptance presentation"' <<<"$original_metadata")
first_revision_version=$((listing_version + 1))
revision=$(jq -cn --argjson metadata "$candidate" --argjson expected "$first_revision_version" \
  '{metadata:$metadata,expectedListingVersion:$expected}')
revision_result=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  --data-binary "$revision" \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/revisions")
revision_id=$(jq -r .revisionId <<<"$revision_result")
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -d '{"reason":"Acceptance presentation revision"}' \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/revisions/$revision_id/activate" >/dev/null
changed_title=$(curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import?locale=en" \
  | jq -r '.metadata.title')
[[ "$changed_title" == 'Temporary acceptance presentation' ]]
stale_payload=$(jq -cn --argjson metadata "$candidate" --argjson expected "$first_revision_version" \
  '{metadata:$metadata,expectedListingVersion:$expected}')
stale_status=$(curl --silent --show-error -o "$tmp/stale-revision.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -H 'Content-Type: application/json' --data-binary "$stale_payload" \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/revisions")
[[ "$stale_status" == 409 ]]
current=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/effective-listing")
current_version=$(jq -r .version <<<"$current")
rollback_payload=$(jq -cn --argjson metadata "$(jq -c .metadata <<<"$current")" \
  --argjson expected "$current_version" --arg source "$baseline_id" \
  '{metadata:$metadata,expectedListingVersion:$expected,rollbackSourceRevisionId:$source}')
rollback_result=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  --data-binary "$rollback_payload" \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/revisions")
rollback_id=$(jq -r .revisionId <<<"$rollback_result")
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -d '{"reason":"Acceptance rollback revision"}' \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/revisions/$rollback_id/activate" >/dev/null
rolled_back_title=$(curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import?locale=en" \
  | jq -r '.metadata.title')
[[ "$rolled_back_title" == "$original_title" ]]
history=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/revisions")
jq -e --arg first "$revision_id" --arg second "$rollback_id" \
  '[.[].revisionId] | index($first) != null and index($second) != null' \
  <<<"$history" >/dev/null
curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import/releases/1.0.0/descriptor" \
  | jq -cS '{descriptor,signature}' > "$tmp/descriptor.after.json"
cmp -s "$tmp/descriptor.before.json" "$tmp/descriptor.after.json"
echo 'Listing revision activation, stale-write rejection, history, rollback and signed descriptor immutability passed.'

curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -d '{"state":"SUSPENDED","reason":"Acceptance suspension"}' \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/moderation" >/dev/null
suspended_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import?locale=en")
[[ "$suspended_status" == 404 ]]
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -d '{"state":"ACTIVE","reason":"Acceptance restoration"}' \
  "$api_base/api/v1/dev-admin/plugins/com.example.dev-import/moderation" >/dev/null
restored_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import?locale=en")
[[ "$restored_status" == 200 ]]
echo 'Plugin suspension hides its public listing and the allowed restoration transition re-enables it.'
published_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' --head \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.dev-import/1.0.0/main/manifests/$digest")
[[ "$published_status" == 200 ]]
published_sidecar_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' --head \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.dev-import/1.0.0/sidecar-companion/manifests/$sidecar_digest")
[[ "$published_sidecar_status" == 200 ]]
curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import/releases/1.0.0/manifest" \
  | jq -e --arg main "$reference" --arg companion "$sidecar_reference" \
    '.image == $main and .services[0].id == "companion" and .services[0].image == $companion' >/dev/null
echo 'Local admin publication passed: staged OCI digest promoted publicly, release signed, descriptor and Core manifest available.'
echo 'Local admin UI, CSRF boundary, runnable Docker-save streaming import, staging digest, release validation, icon/screenshot media, approval, promotion, public catalog rendering and descriptor signing passed.'
