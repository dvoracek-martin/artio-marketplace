#!/usr/bin/env bash
set -euo pipefail

base=${MARKETPLACE_URL:-http://localhost:8080}
registry=${MARKETPLACE_REGISTRY_HOST:-localhost:5000}
publisher_registry=${MARKETPLACE_REGISTRY_PUBLISH_HOST:-localhost:5001}
example="$PWD/examples/reference-plugin"
package=$(mktemp -d)
tags=()
artifact_refs=()
release_refs=()
smoke_container=""
had_python_base=false
python_base='python:3.12-alpine'
docker image inspect "$python_base" >/dev/null 2>&1 && had_python_base=true || true
cleanup() {
  if [[ -n $smoke_container ]]; then docker rm --force "$smoke_container" >/dev/null 2>&1 || true; fi
  docker logout "$publisher_registry" >/dev/null 2>&1 || true
  if ((${#tags[@]})); then docker image rm "${tags[@]}" >/dev/null 2>&1 || true; fi
  if [[ $had_python_base == false ]]; then docker image rm "$python_base" >/dev/null 2>&1 || true; fi
  rm -rf "$package"
}
trap cleanup EXIT
: "${MARKETPLACE_PUBLISHER_TOKEN_FILE:?Set the test publisher-token file path}"

wait_ready() {
  for _ in $(seq 1 60); do
    if curl --fail --silent "$base/actuator/health/readiness" >/dev/null; then return 0; fi
    sleep 2
  done
  echo 'Marketplace did not become ready; inspect Compose logs.' >&2
  return 1
}
wait_ready

v2_status=$(curl --silent --output /dev/null --write-out '%{http_code}' "http://$registry/v2/")
[[ $v2_status == 200 ]] || { echo "Anonymous Zot v2 check returned HTTP $v2_status" >&2; exit 1; }
publisher_v2_status=$(curl --silent --dump-header "$package/publisher-ping.headers" --output /dev/null --write-out '%{http_code}' "http://$publisher_registry/v2/")
[[ $publisher_v2_status == 200 || $publisher_v2_status == 401 ]] || { echo "Publisher Zot v2 ping returned HTTP $publisher_v2_status" >&2; exit 1; }
grep -qi '^WWW-Authenticate: Basic' "$package/publisher-ping.headers" || { echo 'Publisher Zot endpoint did not advertise its Basic-auth challenge.' >&2; exit 1; }
write_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST "http://$registry/v2/public/plugins/anonymous-write/blobs/uploads/")
[[ $write_status == 401 || $write_status == 403 ]] || { echo "Anonymous Zot write unexpectedly returned HTTP $write_status" >&2; exit 1; }

zot_password=${MARKETPLACE_ZOT_TEST_PASSWORD:?Set the test Zot password for Docker login}
printf '%s' "$zot_password" | docker login "$publisher_registry" --username publisher --password-stdin >/dev/null
export MARKETPLACE_PUBLISHER_TOKEN_FILE
accept='application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json'

resolve_digest() {
  local repository=$1 tag=$2
  curl --fail --silent --show-error --head --header "Accept: $accept" \
    "http://$registry/v2/$repository/manifests/$tag" |
    awk 'BEGIN{IGNORECASE=1} /^Docker-Content-Digest:/ {gsub("\r", ""); print $2}'
}

for version in 1.2.0 1.3.0; do
  runtime_version=1
  [[ $version == 1.3.0 ]] && runtime_version=2
  main_tag="$publisher_registry/public/plugins/artio-reference:$version"
  sidecar_tag="$publisher_registry/public/plugins/artio-reference-companion:$version"
  tags+=("$main_tag" "$sidecar_tag")
  docker build --platform linux/amd64 --build-arg "ARTIO_REFERENCE_VERSION=$runtime_version" -t "$main_tag" "$example"
  docker build --platform linux/amd64 --build-arg "ARTIO_REFERENCE_VERSION=$runtime_version" -t "$sidecar_tag" "$example"
  docker push "$main_tag" >/dev/null
  docker push "$sidecar_tag" >/dev/null

  main_digest=$(resolve_digest public/plugins/artio-reference "$version")
  sidecar_digest=$(resolve_digest public/plugins/artio-reference-companion "$version")
  [[ $main_digest =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "Missing main image digest for $version." >&2; exit 1; }
  [[ $sidecar_digest =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "Missing sidecar image digest for $version." >&2; exit 1; }
  main_ref="$registry/public/plugins/artio-reference@$main_digest"
  sidecar_ref="$registry/public/plugins/artio-reference-companion@$sidecar_digest"
  tags+=("$main_ref" "$sidecar_ref")
  artifact_refs+=("$main_ref" "$sidecar_ref")
  release_refs+=("$main_ref")

  release_dir="$package/$version"
  mkdir -p "$release_dir/media"
  cat >"$release_dir/manifest.json" <<JSON
{
  "manifestVersion":"1",
  "id":"marketplace.reference",
  "name":"Artio Reference Extension",
  "vendor":"Artio Marketplace",
  "version":"$version",
  "description":"Runnable reference extension for validating Marketplace distribution with the Artio Core runtime.",
  "minimumCoreVersion":"0.1.0",
  "maximumCoreVersion":"9.9.9",
  "image":"$main_ref",
  "dependencies":[],"permissions":["payment.process"],"configuration":{},"extensionPoints":["payment.provider.v1"],
  "healthPath":"/health","diagnosticsPath":"/diagnostics",
  "geographicAvailability":{"scope":"GLOBAL","countries":[]},
  "services":[{"id":"companion","image":"$sidecar_ref","port":8080,"healthPath":"/health","dependsOn":["main"]}],
  "persistentPaths":["/var/lib/artio-reference"]
}
JSON
  cat >"$release_dir/catalog.json" <<JSON
{
  "schemaVersion":1,"pluginId":"marketplace.reference","slug":"artio-reference-extension",
  "title":{"en":"Artio Reference Extension","de":"Artio Referenzerweiterung"},
  "tagline":{"en":"A runnable Core-compatible plugin for acceptance testing","de":"Ein ausführbares Core-kompatibles Plugin für Abnahmetests"},
  "description":{"en":"A small authenticated extension with deterministic invocation, health checks, and a companion service."},
  "releaseNotes":{"en":"Reference release $version for Marketplace and Core compatibility verification."},
  "vendorDisplayName":"Artio Marketplace","author":"Artio Marketplace engineering",
  "categories":["other"],"tags":["reference","acceptance","oci"],"pricingModel":"FREE",
  "icon":"media/icon.png","screenshots":[{"file":"media/screenshot.png","order":0,"alt":{"en":"Reference extension test card"},"caption":{"en":"Reference extension catalog listing"}}]
}
JSON
  printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDvkAAAAASUVORK5CYII=' | base64 -d >"$release_dir/media/icon.png"
  cp "$release_dir/media/icon.png" "$release_dir/media/screenshot.png"
  ./scripts/publish-plugin.sh --dry-run "$release_dir" "$base"
  ./scripts/publish-plugin.sh "$release_dir" "$base"
  ./scripts/publish-plugin.sh "$release_dir" "$base"

  docker pull "$main_ref" >/dev/null
  docker pull "$sidecar_ref" >/dev/null
  curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference/releases/$version/manifest" >/dev/null
done

docker logout "$publisher_registry" >/dev/null 2>&1 || true
docker compose --env-file "${MARKETPLACE_ENV_FILE:-.env}" -f deployment/compose.yaml restart marketplace
wait_ready
for version in 1.2.0 1.3.0; do
  curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference/releases/$version/manifest" >/dev/null
done
curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference?locale=de" | grep -q 'Artio Referenzerweiterung'
if ((${#tags[@]})); then docker image rm "${tags[@]}" >/dev/null; fi
tags=()
for artifact_ref in "${artifact_refs[@]}"; do
  docker pull "$artifact_ref" >/dev/null
  tags+=("$artifact_ref")
done
smoke_container="marketplace-reference-smoke-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
smoke_token=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
docker run --detach --name "$smoke_container" --publish '127.0.0.1::8080' \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m --cap-drop ALL --security-opt no-new-privileges:true \
  --memory 128m --cpus 0.5 --env "ARTIO_EXTENSION_TOKEN=$smoke_token" \
  --env ARTIO_REFERENCE_VERSION=1 "${release_refs[0]}" >/dev/null
smoke_port=$(docker port "$smoke_container" 8080/tcp | sed 's/.*://')
for _ in $(seq 1 30); do
  if curl --fail --silent "http://127.0.0.1:$smoke_port/health" >/dev/null; then break; fi
  sleep 1
done
curl --fail --silent "http://127.0.0.1:$smoke_port/diagnostics" | grep -q '"runtimeVersion":"1"'
invoke='{"protocolVersion":1,"extensionPoint":"payment.provider.v1","operation":"echo","idempotencyKey":"marketplace-smoke-1","configuration":{},"payload":{"amount":42}}'
invoke_headers=(-H "Authorization: Bearer $smoke_token" -H 'X-Artio-Extension-Api: 1' -H 'Idempotency-Key: marketplace-smoke-1' -H 'Content-Type: application/json')
first_response=$(curl --fail --silent --show-error "http://127.0.0.1:$smoke_port/v1/invoke" "${invoke_headers[@]}" --data "$invoke")
retry_response=$(curl --fail --silent --show-error "http://127.0.0.1:$smoke_port/v1/invoke" "${invoke_headers[@]}" --data "$invoke")
[[ $first_response == "$retry_response" ]] || { echo 'Reference plugin invocation retry was not deterministic.' >&2; exit 1; }
grep -q '"runtimeVersion":"1"' <<<"$first_response"
unauthorized_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
  -H 'X-Artio-Extension-Api: 1' -H 'Idempotency-Key: marketplace-smoke-denied' \
  -H 'Content-Type: application/json' --data "$invoke" "http://127.0.0.1:$smoke_port/v1/invoke")
[[ $unauthorized_status == 401 ]] || { echo 'Reference plugin invocation accepted a request without its runtime token.' >&2; exit 1; }
docker rm --force "$smoke_container" >/dev/null
smoke_container=""
curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference?locale=de" | grep -q 'Artio Referenzerweiterung'
curl --fail --silent "$base/api/v1/marketplace/categories?locale=fr" | grep -q 'Autre'

if [[ -n ${ARTIO_CORE_PATH:-} ]]; then
  core_path=$(cd "$ARTIO_CORE_PATH" && pwd)
  export ARTIO_TEST_EXTENSION_IMAGE_V1=${release_refs[0]}
  export ARTIO_TEST_EXTENSION_IMAGE_V2=${release_refs[1]}
  (cd "$core_path" && bash ./gradlew --no-daemon :artio-rest-api:integrationTest --rerun \
    --tests io.artio.api.extensions.DockerExtensionRuntimeIntegrationTests \
    --tests io.artio.api.extensions.PackagedExtensionControllerIntegrationTests \
    --tests io.artio.api.catalog.CatalogApiIntegrationTests.extensionLifecycleEnforcesDependencyStateAndVersionBeforeRuntimeCalls)
  echo 'Pinned Core versioned admin lifecycle, Docker runtime and packaged controller integration tests passed with Marketplace-published image digests.'
fi

rate_limit=${MARKETPLACE_PUBLIC_RATE_LIMIT_PER_MINUTE:-120}
rate_base=${base/localhost/127.0.0.1}
seq 1 "$((rate_limit + 20))" | xargs -P 16 -I '{}' sh -c \
  'curl --silent --output /dev/null --write-out "%{http_code}\n" "$1/api/v1/marketplace/categories"' _ "$rate_base" \
  >"$package/rate-limit-statuses"
grep -qx '429' "$package/rate-limit-statuses" || {
  echo 'Public API rate limit did not return 429 during a concurrent request burst.' >&2
  exit 1
}
retry_after=$(curl --silent --dump-header "$package/rate-limit.headers" --output "$package/rate-limit.json" "$base/api/v1/marketplace/categories" -w '%{http_code}')
[[ $retry_after == 429 ]] && grep -qi '^Retry-After:' "$package/rate-limit.headers" && grep -q 'RATE_LIMITED' "$package/rate-limit.json" || {
  echo 'Rate limited API response did not include its retry interval and error code.' >&2
  exit 1
}

echo 'Docker acceptance passed: anonymous pulls and denied writes, authenticated OCI pushes, four digest-pinned main/sidecar artifacts, two immutable release publications with idempotent retries, localized catalog, manifest retrieval, a restricted real plugin health/authenticated invocation/idempotency smoke test, and bounded public API rate limiting. When ARTIO_CORE_PATH is set, Core Docker lifecycle checks also run. Test-local containers, images and files are removed by the exit trap.'
