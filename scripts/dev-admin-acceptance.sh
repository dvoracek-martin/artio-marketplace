#!/usr/bin/env bash
set -euo pipefail

api_base="${MARKETPLACE_DEV_API_BASE:-http://127.0.0.1:18080}"
management="${MARKETPLACE_DEV_MANAGEMENT_URL:-http://127.0.0.1:18081/actuator/health/readiness}"
registry_port="${MARKETPLACE_DEV_REGISTRY_PORT:-15000}"
importer_password_file="${MARKETPLACE_LOCAL_IMPORTER_PASSWORD_FILE:-secrets/zot-importer-password.txt}"
tmp=$(mktemp -d)
image='artio/dev-admin-archive-fixture:1.0.0'
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

cat > "$tmp/Dockerfile" <<'EOF'
FROM python:3.12-alpine
CMD ["python3", "-c", "print('Artio archive-import acceptance fixture')"]
EOF
docker build --quiet --tag "$image" "$tmp" >/dev/null
docker save --output "$tmp/fixture.tar" "$image"
echo "Docker-save fixture created ($(stat -c '%s' "$tmp/fixture.tar") bytes)."

job=$(curl --silent --show-error --fail -b "$tmp/cookies/session.txt" \
  -H "Origin: $api_base" -H "X-Artio-CSRF: $csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dev-admin-$(openssl rand -hex 12)" \
  -d '{"pluginId":"com.example.dev-import","version":"1.0.0","role":"MAIN","archiveTag":"artio/dev-admin-archive-fixture:1.0.0"}' \
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
  -H 'X-Archive-Image: artio/dev-admin-archive-fixture:1.0.0' \
  --data-binary "@$tmp/fixture.tar" "$api_base/api/v1/dev-admin/imports/$job_id/archive")
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
echo 'Local admin UI and CSRF boundary passed; Docker-save archive streamed into private Zot staging with its registry digest verified.'
