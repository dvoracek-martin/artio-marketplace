#!/usr/bin/env bash
set -euo pipefail

base=${MARKETPLACE_URL:-http://localhost:8080}
registry=${MARKETPLACE_REGISTRY_HOST:-localhost:5000}
publisher_registry=${MARKETPLACE_REGISTRY_PUBLISH_HOST:-localhost:5001}
package=$(mktemp -d)
registry_main="$publisher_registry/public/plugins/marketplace-fixture-main:1.2.0"
registry_sidecar="$publisher_registry/public/plugins/marketplace-fixture-sidecar:1.2.0"
had_busybox=false
had_alpine=false
docker image inspect busybox:1.36.1 >/dev/null 2>&1 && had_busybox=true || true
docker image inspect alpine:3.22 >/dev/null 2>&1 && had_alpine=true || true
cleanup() {
  docker logout "$publisher_registry" >/dev/null 2>&1 || true
  docker image rm "$registry_main" "$registry_sidecar" >/dev/null 2>&1 || true
  if [[ $had_busybox == false ]]; then docker image rm busybox:1.36.1 >/dev/null 2>&1 || true; fi
  if [[ $had_alpine == false ]]; then docker image rm alpine:3.22 >/dev/null 2>&1 || true; fi
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
docker pull busybox:1.36.1 >/dev/null
docker tag busybox:1.36.1 "$registry_main"
docker push "$registry_main" >/dev/null
main_digest=$(curl --fail --silent --show-error --head --header 'Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json' "http://$registry/v2/public/plugins/marketplace-fixture-main/manifests/1.2.0" | awk 'BEGIN{IGNORECASE=1} /^Docker-Content-Digest:/ {gsub("\r", ""); print $2}')
[[ $main_digest =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'Zot did not return the expected main image digest.' >&2; exit 1; }
main_ref="$registry/public/plugins/marketplace-fixture-main@$main_digest"
docker pull alpine:3.22 >/dev/null
docker tag alpine:3.22 "$registry_sidecar"
docker push "$registry_sidecar" >/dev/null
sidecar_digest=$(curl --fail --silent --show-error --head --header 'Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json' "http://$registry/v2/public/plugins/marketplace-fixture-sidecar/manifests/1.2.0" | awk 'BEGIN{IGNORECASE=1} /^Docker-Content-Digest:/ {gsub("\r", ""); print $2}')
[[ $sidecar_digest =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'Zot did not return the expected sidecar image digest.' >&2; exit 1; }
sidecar_ref="$registry/public/plugins/marketplace-fixture-sidecar@$sidecar_digest"

cat >"$package/manifest.json" <<JSON
{
  "manifestVersion":"1",
  "id":"marketplace.fixture",
  "name":"Marketplace Reference Fixture",
  "vendor":"Artio Marketplace CI",
  "version":"1.2.0",
  "description":"Runnable OCI manifest/index acceptance fixture.",
  "minimumCoreVersion":"0.1.0",
  "maximumCoreVersion":"0.9.0",
  "image":"$main_ref",
  "dependencies":[],"permissions":[],"configuration":{},"extensionPoints":[],
  "healthPath":"/health","diagnosticsPath":"/diagnostics",
  "geographicAvailability":{"scope":"GLOBAL","countries":[]},
  "services":[{"id":"fixture-sidecar","image":"$sidecar_ref","port":8080,"healthPath":"/","dependsOn":["main"]}]
}
JSON
cat >"$package/catalog.json" <<'JSON'
{
  "schemaVersion":1,"pluginId":"marketplace.fixture","slug":"marketplace-fixture",
  "title":{"en":"Marketplace Fixture","de":"Marketplace Fixture"},
  "tagline":{"en":"OCI publication acceptance","de":"OCI Publikationstest"},
  "description":{"en":"A public listing used to verify registry and catalog behavior."},
  "releaseNotes":{"en":"Version 1.2.0 acceptance release."},
  "vendorDisplayName":"Artio Marketplace CI","author":"Artio Marketplace CI",
  "categories":["other"],"tags":["acceptance","oci"],"pricingModel":"FREE",
  "icon":"icon.png","screenshots":[{"file":"screenshot.png","order":0,"alt":{"en":"Fixture screenshot"}}]
}
JSON
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDvkAAAAASUVORK5CYII=' | base64 -d >"$package/icon.png"
cp "$package/icon.png" "$package/screenshot.png"

export MARKETPLACE_PUBLISHER_TOKEN_FILE
./scripts/publish-plugin.sh --dry-run "$package" "$base"
./scripts/publish-plugin.sh "$package" "$base"
./scripts/publish-plugin.sh "$package" "$base"
docker logout "$publisher_registry" >/dev/null 2>&1 || true
docker pull "$main_ref" >/dev/null
docker pull "$sidecar_ref" >/dev/null
curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.fixture/releases/1.2.0/manifest" >/dev/null
curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.fixture?locale=de" | grep -q 'OCI Publikationstest'
curl --fail --silent "$base/api/v1/marketplace/categories?locale=fr" | grep -q 'Autre'

echo 'Docker acceptance passed: anonymous reads, write denial, authenticated OCI push, main and sidecar digest reads/pulls, validate, publish, idempotent retry, localized public catalog, and manifest endpoint. Local test tags and any test-only base images were removed by the exit trap.'
