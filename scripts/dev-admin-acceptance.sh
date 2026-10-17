#!/usr/bin/env bash
set -euo pipefail

report_failure() {
  local status=$?
  printf 'Dev admin acceptance failed at script line %s (exit %s).\n' \
    "${BASH_LINENO[0]:-unknown}" "$status" >&2
  return "$status"
}
trap report_failure ERR

api_base="${MARKETPLACE_DEV_API_BASE:-http://127.0.0.1:18080}"
management="${MARKETPLACE_DEV_MANAGEMENT_URL:-http://127.0.0.1:18081/actuator/health/readiness}"
registry_port="${MARKETPLACE_DEV_REGISTRY_PORT:-15000}"
importer_password_file="${MARKETPLACE_LOCAL_IMPORTER_PASSWORD_FILE:-secrets/zot-importer-password.txt}"
tmp=$(mktemp -d)
image="artio/dev-admin-archive-fixture:accept-$(openssl rand -hex 8)"
# OCI image-layout archives name references by tag only; unlike docker-save,
# they do not carry the source repository name in ref.name.
sidecar_image="multi-$(openssl rand -hex 8)"
base_image='python:3.12-alpine'
base_was_present=0
declare -a cleanup_image_ids=()
docker image inspect "$base_image" >/dev/null 2>&1 && base_was_present=1
cleanup() {
  for image_id in "${cleanup_image_ids[@]}"; do docker image rm "$image_id" >/dev/null 2>&1 || true; done
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
wrong_origin_status=$(curl --silent --show-error -o "$tmp/origin-response.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H 'Origin: http://attacker.example' \
  -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -X POST -d '{}' "$api_base/api/v1/dev-admin/imports")
[[ "$wrong_origin_status" == 403 ]]
forged_host_status=$(curl --silent --show-error -o "$tmp/host-response.html" -w '%{http_code}' \
  -H 'Host: marketplace.attacker.example' -H 'Origin: http://marketplace.attacker.example' \
  "$api_base/api/v1/dev-admin/session")
[[ "$forged_host_status" == 400 || "$forged_host_status" == 403 || "$forged_host_status" == 404 ]] || {
  echo "Forged Host was not rejected (HTTP $forged_host_status): $(cat "$tmp/host-response.html")" >&2
  exit 1
}
forwarded_host_status=$(curl --silent --show-error -o "$tmp/forwarded-response.html" -w '%{http_code}' \
  -H 'X-Forwarded-Host: 127.0.0.1' "$api_base/api/v1/dev-admin/session")
[[ "$forwarded_host_status" == 403 || "$forwarded_host_status" == 404 ]]
echo 'CSRF, cross-origin POST, forged Host and forwarded-host rejection passed.'

docker build --quiet --build-arg ARTIO_REFERENCE_VERSION=1.0.0 \
  --tag "$image" examples/reference-plugin >/dev/null
docker save --output "$tmp/fixture.tar" "$image"
password=$(<"$importer_password_file")
echo "Docker-save fixture created ($(stat -c '%s' "$tmp/fixture.tar") bytes)."

# A validly streamed tar with a hostile path must fail inspection before either
# staging or public Zot receives an image manifest.
python3 - "$tmp/hostile-path.tar" <<'PY'
import io
import sys
import tarfile

with tarfile.open(sys.argv[1], 'w') as archive:
    entry = tarfile.TarInfo('../../escape')
    entry.size = 7
    archive.addfile(entry, io.BytesIO(b'blocked'))
PY
hostile_job_payload=$(jq -cn --arg image "$image" \
  '{pluginId:"com.example.hostile-archive",version:"1.0.0",role:"MAIN",archiveTag:$image}')
hostile_job=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-hostile-$(openssl rand -hex 12)" \
  --data-binary "$hostile_job_payload" "$api_base/api/v1/dev-admin/imports")
hostile_job_id=$(jq -r .jobId <<<"$hostile_job")
hostile_upload_status=$(curl --silent --show-error -o "$tmp/hostile-upload.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -H 'Content-Type: application/octet-stream' -H 'X-Archive-Name: hostile-path.tar' \
  -H "X-Archive-Image: $image" -X PUT --data-binary "@$tmp/hostile-path.tar" \
  "$api_base/api/v1/dev-admin/imports/$hostile_job_id/archive")
[[ "$hostile_upload_status" == 202 ]]
hostile_state=''
for _ in $(seq 1 60); do
  hostile_result=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
    "$api_base/api/v1/dev-admin/imports/$hostile_job_id")
  hostile_state=$(jq -r .state <<<"$hostile_result")
  [[ "$hostile_state" == FAILED || "$hostile_state" == STAGED ]] && break
  sleep 2
done
[[ "$hostile_state" == FAILED ]]
hostile_staging_tag="import-${hostile_job_id//-/}"
hostile_staging_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' --head \
  -u "oci-importer:$password" \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/staging/plugins/com.example.hostile-archive/1.0.0/main/manifests/$hostile_staging_tag")
[[ "$hostile_staging_status" == 404 ]]
hostile_public_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' --head \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.hostile-archive/1.0.0/main/manifests/$hostile_staging_tag")
[[ "$hostile_public_status" == 404 ]]
echo 'Hostile archive path was rejected before creating private or public Zot manifests.'

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
docker buildx build --quiet --platform linux/amd64,linux/arm64 \
  --provenance=false \
  --build-arg ARTIO_REFERENCE_VERSION=1.0.0 \
  --output "type=oci,name=$sidecar_image,dest=$tmp/sidecar-oci.tar" examples/reference-plugin
mapfile -t oci_archive_tags < <(tar -xOf "$tmp/sidecar-oci.tar" index.json \
  | jq -r '.manifests[]?.annotations["org.opencontainers.image.ref.name"] // empty')
if ((${#oci_archive_tags[@]} != 1)); then
  echo "Expected one named OCI image in the sidecar archive; found: ${oci_archive_tags[*]:-<none>}" >&2
  exit 1
fi
sidecar_archive_tag=${oci_archive_tags[0]}
sidecar_payload=$(jq -cn --arg image "$sidecar_archive_tag" \
  '{pluginId:"com.example.dev-import",version:"1.0.0",role:"SIDECAR:companion",archiveTag:$image}')
sidecar_job=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-sidecar-$(openssl rand -hex 12)" \
  --data-binary "$sidecar_payload" "$api_base/api/v1/dev-admin/imports")
sidecar_job_id=$(jq -r .jobId <<<"$sidecar_job")
[[ "$sidecar_job_id" =~ ^[0-9a-f-]{36}$ ]]
sidecar_upload_status=$(curl --silent --show-error -o "$tmp/sidecar-upload.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -H 'Content-Type: application/octet-stream' -H 'X-Archive-Name: sidecar-oci.tar' \
  -H "X-Archive-Image: $sidecar_archive_tag" -X PUT --data-binary "@$tmp/sidecar-oci.tar" \
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
jq -e 'any(.platforms[]; .os == "linux" and .architecture == "amd64") and any(.platforms[]; .os == "linux" and .architecture == "arm64")' \
  <<<"$sidecar_result" >/dev/null
curl --silent --show-error --fail --head \
  -u "oci-importer:$password" \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json' \
  "http://127.0.0.1:$registry_port/v2/staging/plugins/com.example.dev-import/1.0.0/sidecar-companion/manifests/import-${sidecar_job_id//-/}" \
  | grep -qi "Docker-Content-Digest: $sidecar_digest"
echo 'Main docker-save and companion two-platform OCI sidecar images were independently staged with verified digests.'

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
  "services":[{"id":"companion","image":"$sidecar_reference","port":8080,"healthPath":"/health","configurationBindings":{},"dependsOn":[],"persistentPaths":[],"internalPorts":[],"memoryLimitMb":128}]
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

plugin_before_approval=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  "$api_base/api/v1/dev-admin/plugins")
admin_plugin_page=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  "$api_base/api/v1/dev-admin/plugins/page?page=0&size=1&q=com.example.dev-import&category=ALL&moderation=ACTIVE")
jq -e '.page == 0 and .size == 1 and .totalElements >= 1 and .totalPages >= 1 and (.content | length) == 1 and .content[0].pluginId == "com.example.dev-import"' \
  <<<"$admin_plugin_page" >/dev/null || {
  echo "Server-side admin plugin search/pagination failed: $admin_plugin_page" >&2
  exit 1
}
plugin_moderation=$(jq -r '.[] | select(.pluginId == "com.example.dev-import") | .moderationState' \
  <<<"$plugin_before_approval")
[[ "$plugin_moderation" == ACTIVE ]] || {
  echo "Plugin moderation blocks the acceptance approval: $plugin_before_approval" >&2
  exit 1
}
approval_status=$(curl --silent --show-error -o "$tmp/approval.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -d '{"reason":"Isolated local admin acceptance review"}' \
  "$api_base/api/v1/dev-admin/releases/$release_id/approve")
[[ "$approval_status" == 200 ]] || {
  echo "Local dev release approval returned HTTP $approval_status: $(cat "$tmp/approval.json")" >&2
  echo "Release state: $release_after_restart" >&2
  echo "Plugin state: $plugin_before_approval" >&2
  exit 1
}
publish_status=$(curl --silent --show-error -o "$tmp/publish.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -X POST \
  "$api_base/api/v1/dev-admin/releases/$release_id/publish")
[[ "$publish_status" == 200 ]] || {
  release_after_publish=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
    "$api_base/api/v1/dev-admin/releases/$release_id")
  echo "Local dev release publication returned HTTP $publish_status: $(cat "$tmp/publish.json")" >&2
  echo "Release after attempted publication: $release_after_publish" >&2
  exit 1
}
published=$(cat "$tmp/publish.json")
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
current_version=$(jq -r .listingRevisionVersion <<<"$current")
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

# Check the published Core manifest while release 1.0.0 is still listed; the
# later release-scoped unlisting check must make this public route return 404.
curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import/releases/1.0.0/manifest" \
  | jq -e --arg main "$reference" --arg companion "$sidecar_reference" \
    '.image == $main and .services[0].id == "companion" and .services[0].image == $companion' >/dev/null

# Publish a higher SemVer release with the same immutable OCI digests. This lets
# acceptance verify release-scoped unlisting and terminal revocation behavior.
second_manifest=$(jq '.version="1.0.1"' "$tmp/manifest.json")
second_catalog=$(jq '.title.en="Dev Import Acceptance 1.0.1"' "$tmp/catalog.json")
second_submission=$(jq -cn --argjson manifest "$second_manifest" --argjson catalog "$second_catalog" \
  '{manifest:$manifest,catalog:$catalog}')
second_validation=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  --data-binary "$second_submission" "$api_base/api/v1/dev-admin/releases/validate")
jq -e '.valid == true and (.errors | length == 0)' <<<"$second_validation" >/dev/null || {
  echo "Second release validation failed: $second_validation" >&2
  exit 1
}
second_draft=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-second-release-$(openssl rand -hex 12)" \
  --data-binary "$second_submission" "$api_base/api/v1/dev-admin/releases")
second_release_id=$(jq -r .releaseId <<<"$second_draft")
[[ "$second_release_id" =~ ^[0-9a-f-]{36}$ ]]
second_asset=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -F "kind=icon" -F "file=@$tmp/icon.png;type=image/png" \
  "$api_base/api/v1/dev-admin/releases/$second_release_id/assets")
second_asset_id=$(jq -r .assetId <<<"$second_asset")
[[ "$second_asset_id" =~ ^sha256-[a-f0-9]{64}$ ]]
second_shot=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -F "kind=screenshot" -F "file=@$tmp/icon.png;type=image/png" \
  "$api_base/api/v1/dev-admin/releases/$second_release_id/assets")
second_shot_id=$(jq -r .assetId <<<"$second_shot")
[[ "$second_shot_id" =~ ^sha256-[a-f0-9]{64}$ ]]
second_catalog=$(jq --arg icon "$second_asset_id" --arg shot "$second_shot_id" \
  '.icon=$icon | .screenshots[0].assetId=$shot' <<<"$second_catalog")
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -X PUT --data-binary "$second_catalog" \
  "$api_base/api/v1/dev-admin/releases/$second_release_id/catalog" >/dev/null
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -d '{"reason":"Release-scoped transition acceptance"}' \
  "$api_base/api/v1/dev-admin/releases/$second_release_id/approve" >/dev/null
second_published=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -X POST \
  "$api_base/api/v1/dev-admin/releases/$second_release_id/publish")
jq -e '.state == "PUBLISHED" and .signatureState == "SIGNED"' <<<"$second_published" >/dev/null
second_descriptor=$(curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import/releases/1.0.1/descriptor")
jq -e '.descriptor.version == "1.0.1" and .signature.algorithm == "Ed25519"' \
  <<<"$second_descriptor" >/dev/null

unlist_status=$(curl --silent --show-error -o "$tmp/unlist.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -X POST "$api_base/api/v1/dev-admin/releases/$release_id/unlist")
[[ "$unlist_status" == 200 ]]
plugin_after_unlist=$(curl --silent --show-error -o /dev/null -w '%{http_code}' \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import?locale=en")
latest_after_unlist=$(curl --silent --show-error --fail \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import/releases/1.0.1/manifest")
unlisted_release_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' \
  "$api_base/api/v1/marketplace/plugins/com.example.dev-import/releases/1.0.0")
[[ "$plugin_after_unlist" == 200 && "$unlisted_release_status" == 404 ]]
jq -e '.version == "1.0.1"' <<<"$latest_after_unlist" >/dev/null

revoke_status=$(curl --silent --show-error -o "$tmp/revoke.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -H 'Content-Type: application/json' -d '{"category":"SECURITY","reason":"Isolated terminal-state acceptance"}' \
  "$api_base/api/v1/dev-admin/releases/$second_release_id/revoke")
[[ "$revoke_status" == 200 ]]
revoked_publish_status=$(curl --silent --show-error -o "$tmp/revoked-publish.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -X POST \
  "$api_base/api/v1/dev-admin/releases/$second_release_id/publish")
revoked_delete_status=$(curl --silent --show-error -o "$tmp/revoked-delete.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -X DELETE "$api_base/api/v1/dev-admin/releases/$second_release_id")
[[ "$revoked_publish_status" == 409 && "$revoked_delete_status" == 409 ]]
echo 'Release-scoped unlisting preserved the newer listing; revoked history rejected republish and deletion.'

published_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' --head \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.dev-import/1.0.0/main/manifests/$digest")
[[ "$published_status" == 200 ]]
curl --silent --show-error --fail --head \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.dev-import/1.0.0/main/manifests/$digest" \
  | grep -qi "Docker-Content-Digest: $digest"
published_sidecar_status=$(curl --silent --show-error -D "$tmp/sidecar-public.headers" -o /dev/null -w '%{http_code}' --head \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.dev-import/1.0.0/sidecar-companion/manifests/$sidecar_digest")
[[ "$published_sidecar_status" == 200 ]]
grep -qi "Docker-Content-Digest: $sidecar_digest" "$tmp/sidecar-public.headers"
sidecar_repository="localhost:$registry_port/public/plugins/com.example.dev-import/1.0.0/sidecar-companion"
sidecar_public_index=$(curl --silent --show-error --fail \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.dev-import/1.0.0/sidecar-companion/manifests/$sidecar_digest")
amd64_digest=$(jq -er '[.manifests[] | select(.platform.os == "linux" and .platform.architecture == "amd64") | .digest] | first' \
  <<<"$sidecar_public_index")
arm64_digest=$(jq -er '[.manifests[] | select(.platform.os == "linux" and .platform.architecture == "arm64") | .digest] | first' \
  <<<"$sidecar_public_index")
[[ "$amd64_digest" =~ ^sha256:[a-f0-9]{64}$ && "$arm64_digest" =~ ^sha256:[a-f0-9]{64}$ ]]
amd64_pull_ref="$sidecar_repository@$amd64_digest"
arm64_pull_ref="$sidecar_repository@$arm64_digest"
cleanup_image_ids+=("$amd64_pull_ref" "$arm64_pull_ref")
docker pull --platform linux/amd64 "$amd64_pull_ref" >/dev/null
amd64_image_id=$(docker image inspect --format '{{.Id}}' "$amd64_pull_ref")
cleanup_image_ids+=("$amd64_image_id")
[[ "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$amd64_pull_ref")" == 'linux/amd64' ]]
docker pull --platform linux/arm64 "$arm64_pull_ref" >/dev/null
arm64_image_id=$(docker image inspect --format '{{.Id}}' "$arm64_pull_ref")
cleanup_image_ids+=("$arm64_image_id")
[[ "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$arm64_pull_ref")" == 'linux/arm64' ]]
echo 'Public sidecar OCI index resolves to platform-specific amd64 and arm64 manifest digests; both child digests pull with their expected architectures.'

# A new never-approved draft may be removed; published history and its public
# digest must remain protected from physical deletion.
draft_manifest=$(jq '.version="1.0.2"' "$tmp/manifest.json")
draft_catalog=$(jq '.title.en="Draft deletion acceptance"' \
  "$tmp/catalog-with-asset.json")
jq -cn --argjson manifest "$draft_manifest" --argjson catalog "$draft_catalog" \
  '{manifest:$manifest,catalog:$catalog}' > "$tmp/delete-draft-submission.json"
draft_validation=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  --data-binary "@$tmp/delete-draft-submission.json" \
  "$api_base/api/v1/dev-admin/releases/validate")
jq -e '.valid == true and (.errors | length == 0)' <<<"$draft_validation" >/dev/null
delete_draft=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-delete-draft-$(openssl rand -hex 12)" \
  --data-binary "@$tmp/delete-draft-submission.json" \
  "$api_base/api/v1/dev-admin/releases")
delete_draft_id=$(jq -r .releaseId <<<"$delete_draft")
draft_delete_status=$(curl --silent --show-error -o "$tmp/delete-draft.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -X DELETE "$api_base/api/v1/dev-admin/releases/$delete_draft_id")
[[ "$draft_delete_status" == 200 ]]
draft_audit=$(docker compose --project-name "${MARKETPLACE_DEV_PROJECT:?}" \
  --env-file "${MARKETPLACE_DEV_ENV_FILE:?}" -f deployment/compose.yaml \
  -f deployment/compose.dev.yaml exec -T postgres psql -U artio_marketplace \
  -d artio_marketplace -Atqc "SELECT action || ':' || target FROM marketplace_audit WHERE action='DELETE_DRAFT' ORDER BY audit_id DESC LIMIT 1")
grep -q 'DELETE_DRAFT:com.example.dev-import@1.0.2' <<<"$draft_audit"
published_delete_status=$(curl --silent --show-error -o "$tmp/delete-published.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -X DELETE "$api_base/api/v1/dev-admin/releases/$release_id")
[[ "$published_delete_status" == 409 ]]
plugin_delete_status=$(curl --silent --show-error -o "$tmp/delete-plugin.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -X DELETE "$api_base/api/v1/dev-admin/plugins/com.example.dev-import")
[[ "$plugin_delete_status" == 409 ]]
empty_plugin_id='com.example.empty-delete'
empty_import_payload=$(jq -cn --arg id "$empty_plugin_id" --arg image "$sidecar_archive_tag" \
  '{pluginId:$id,version:"1.0.0",role:"MAIN",archiveTag:$image}')
empty_import=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-empty-import-$(openssl rand -hex 12)" \
  --data-binary "$empty_import_payload" "$api_base/api/v1/dev-admin/imports")
empty_job_id=$(jq -r .jobId <<<"$empty_import")
[[ "$empty_job_id" =~ ^[0-9a-f-]{36}$ ]]
empty_upload_status=$(curl --silent --show-error -o "$tmp/empty-upload.json" -w '%{http_code}' \
  -b "$tmp/cookies/session.txt" -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" \
  -H 'Content-Type: application/octet-stream' -H 'X-Archive-Name: empty-plugin-oci.tar' \
  -H "X-Archive-Image: $sidecar_archive_tag" -X PUT --data-binary "@$tmp/sidecar-oci.tar" \
  "$api_base/api/v1/dev-admin/imports/$empty_job_id/archive")
[[ "$empty_upload_status" == 202 ]]
empty_state=''
for _ in $(seq 1 300); do
  empty_result=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
    "$api_base/api/v1/dev-admin/imports/$empty_job_id")
  empty_state=$(jq -r .state <<<"$empty_result")
  [[ "$empty_state" == STAGED || "$empty_state" == FAILED ]] && break
  sleep 2
done
[[ "$empty_state" == STAGED ]]
empty_reference=$(jq -r .image <<<"$empty_result")
empty_manifest=$(jq --arg id "$empty_plugin_id" --arg image "$empty_reference" \
  '.id=$id | .name="Empty plugin deletion acceptance" | .version="1.0.0" | .image=$image | .services=[]' \
  "$tmp/manifest.json")
empty_catalog=$(jq --arg id "$empty_plugin_id" \
  '.pluginId=$id | .slug="empty-plugin-delete-acceptance" | .title.en="Empty plugin deletion acceptance"' \
  "$tmp/catalog-with-asset.json")
jq -cn --argjson manifest "$empty_manifest" --argjson catalog "$empty_catalog" \
  '{manifest:$manifest,catalog:$catalog}' > "$tmp/empty-plugin-submission.json"
empty_validation=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  --data-binary "@$tmp/empty-plugin-submission.json" "$api_base/api/v1/dev-admin/releases/validate")
jq -e '.valid == true and (.errors | length == 0)' <<<"$empty_validation" >/dev/null || {
  echo "Empty-plugin deletion draft validation failed: $empty_validation" >&2
  exit 1
}
empty_draft=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-empty-draft-$(openssl rand -hex 12)" \
  --data-binary "@$tmp/empty-plugin-submission.json" "$api_base/api/v1/dev-admin/releases")
empty_release_id=$(jq -r .releaseId <<<"$empty_draft")
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -X DELETE \
  "$api_base/api/v1/dev-admin/releases/$empty_release_id" >/dev/null
curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -X DELETE \
  "$api_base/api/v1/dev-admin/plugins/$empty_plugin_id" \
  | jq -e --arg plugin "$empty_plugin_id" '.deleted == true and .pluginId == $plugin' >/dev/null
empty_audit=$(docker compose --project-name "${MARKETPLACE_DEV_PROJECT:?}" \
  --env-file "${MARKETPLACE_DEV_ENV_FILE:?}" -f deployment/compose.yaml \
  -f deployment/compose.dev.yaml exec -T postgres psql -U artio_marketplace \
  -d artio_marketplace -Atqc "SELECT action || ':' || target FROM marketplace_audit WHERE action='DELETE_EMPTY_PLUGIN' ORDER BY audit_id DESC LIMIT 1")
grep -q "DELETE_EMPTY_PLUGIN:$empty_plugin_id" <<<"$empty_audit"
empty_plugin_public_status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' \
  "$api_base/api/v1/marketplace/plugins/$empty_plugin_id")
[[ "$empty_plugin_public_status" == 404 ]]
curl --silent --show-error --fail --head \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' \
  "http://127.0.0.1:$registry_port/v2/public/plugins/com.example.dev-import/1.0.0/main/manifests/$digest" >/dev/null
echo 'Never-approved draft and empty-plugin deletion/audit passed; published release/plugin deletion was rejected and the public OCI digest remained available.'

# Exercise the same publication path through a real Chromium session against
# the live dev-only application, reusing the actual Docker-save archive above.
live_plugin_id="com.example.live-browser-$(openssl rand -hex 5)"
(
  cd marketplace-admin-ui
  MARKETPLACE_LIVE_BASE_URL="$api_base" \
    MARKETPLACE_LIVE_PLUGIN_ID="$live_plugin_id" \
    MARKETPLACE_LIVE_ARCHIVE_PATH="$(realpath "$tmp/fixture.tar")" \
    MARKETPLACE_LIVE_ARCHIVE_TAG="$image" \
    MARKETPLACE_LIVE_REGISTRY_BASE="http://127.0.0.1:$registry_port" \
    npm run test:e2e:live
)
echo 'Live Chromium UI uploaded the real Docker-save archive, published and signed the release, fetched its official descriptor, and pulled its public OCI digest.'
echo 'Local admin publication passed: staged OCI digest promoted publicly, release signed, descriptor and Core manifest available.'
echo 'Local admin UI, CSRF boundary, Docker-save streaming import, multi-platform OCI sidecar upload and amd64/arm64 pulls, release validation, icon/screenshot media, approval, promotion, public catalog rendering and descriptor signing passed.'
