#!/usr/bin/env bash
set -euo pipefail

base=${MARKETPLACE_URL:-http://localhost:8080}
registry=${MARKETPLACE_REGISTRY_HOST:-localhost:5000}
registry_connect=${MARKETPLACE_REGISTRY_CONNECT_HOST:-$registry}
publisher_registry=${MARKETPLACE_REGISTRY_PUBLISH_HOST:-localhost:5001}
publisher_registry_connect=${MARKETPLACE_REGISTRY_PUBLISH_CONNECT_HOST:-$publisher_registry}
example="$PWD/examples/reference-plugin"
compose=(docker compose --project-name "${MARKETPLACE_COMPOSE_PROJECT:-deployment}" --env-file "${MARKETPLACE_ENV_FILE:-.env}" -f deployment/compose.yaml)
package=$(mktemp -d "$PWD/.acceptance-tmp.XXXXXX")
python=${MARKETPLACE_PYTHON:-python}
export PYTHON="$python"
tags=()
artifact_refs=()
release_refs=()
release_id_120=""
release_id_129=""
main_digest_120=""
sidecar_digest_120=""
smoke_container=""
proxy_container=""
had_python_base=false
python_base='python:3.12-alpine'
docker image inspect "$python_base" >/dev/null 2>&1 && had_python_base=true || true
cleanup() {
  if [[ -n $smoke_container ]]; then docker rm --force "$smoke_container" >/dev/null 2>&1 || true; fi
  if [[ -n $proxy_container ]]; then docker rm --force "$proxy_container" >/dev/null 2>&1 || true; fi
  docker logout "$publisher_registry" >/dev/null 2>&1 || true
  if ((${#tags[@]})); then docker image rm "${tags[@]}" >/dev/null 2>&1 || true; fi
  if [[ $had_python_base == false ]]; then docker image rm "$python_base" >/dev/null 2>&1 || true; fi
  rm -rf "$package"
}
trap cleanup EXIT
: "${MARKETPLACE_PUBLISHER_TOKEN_FILE:?Set the test publisher-token file path}"
: "${MARKETPLACE_ADMIN_TOKEN_FILE:?Set the test admin-token file path}"
: "${MARKETPLACE_REVOCATION_TOKEN_FILE:?Set the test revocation-token file path}"
: "${MARKETPLACE_SIGNING_INGEST_TOKEN_FILE:?Set the test signing-ingest-token file path}"
: "${MARKETPLACE_SIGNING_PRIVATE_KEY_FILE:?Set the test release-signing private-key file path}"
: "${MARKETPLACE_SIGNING_KEY_ID:?Set the trusted release-signing key id}"
publisher_token=$(cat "$MARKETPLACE_PUBLISHER_TOKEN_FILE")
admin_token=$(cat "$MARKETPLACE_ADMIN_TOKEN_FILE")
signing_token=$(cat "$MARKETPLACE_SIGNING_INGEST_TOKEN_FILE")

sign_release() {
  local release_id=$1 version=$2 temp="$package/signature-$1"
  mkdir -p "$temp"
  curl --fail --silent --show-error -H "Authorization: Bearer $publisher_token" \
    "$base/api/v1/admin/marketplace/releases/$release_id/signing-payload" -o "$temp/descriptor.json"
  openssl pkeyutl -sign -rawin -inkey "$MARKETPLACE_SIGNING_PRIVATE_KEY_FILE" \
    -in "$temp/descriptor.json" -out "$temp/signature.bin"
  local signature
  signature=$(base64 -w0 "$temp/signature.bin" | tr '+/' '-_' | tr -d '=')
  "$python" - "$temp/descriptor.json" "$temp/envelope.json" "$MARKETPLACE_SIGNING_KEY_ID" "$signature" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as descriptor:
    payload=json.load(descriptor)
with open(sys.argv[2], 'w', encoding='utf-8') as envelope:
    json.dump({'descriptor':payload,'signature':{'algorithm':'Ed25519','keyId':sys.argv[3],'value':sys.argv[4]}}, envelope, separators=(',',':'))
PY
  curl --fail --silent --show-error -H "Authorization: Bearer $signing_token" \
    -H 'Content-Type: application/json' --data-binary "@$temp/envelope.json" \
    "$base/api/v1/admin/marketplace/releases/$release_id/signature" >/dev/null
  curl --fail --silent --show-error \
    "$base/api/v1/marketplace/plugins/marketplace.reference/releases/$version/descriptor" \
    | "$python" -c 'import json,sys; x=json.load(sys.stdin); assert x["signature"]["algorithm"] == "Ed25519" and x["descriptor"]["version"] == sys.argv[1]' "$version"
}
verify_advisory_feed() {
  local expected_banned_version=${1:-}
  curl --fail --silent --show-error "$base/api/v1/marketplace/security/advisories" \
    -o "$package/advisories.json"
  "$python" - "$package/advisories.json" "$package/advisory-payload.json" \
    "$package/advisory-signature.bin" "$expected_banned_version" <<'PY'
import base64, json, sys
with open(sys.argv[1], encoding='utf-8') as source:
    envelope=json.load(source)
payload=envelope['payload']
signature=envelope['signature']
assert signature['algorithm'] == 'Ed25519'
assert signature['keyId'] == payload['keyId']
assert payload['schemaVersion'] == 1 and payload['sequence'] > 0
assert payload['expiresAt'] > payload['issuedAt']
assert isinstance(payload['advisories'], list)
for advisory in payload['advisories']:
    assert advisory['securityStatus'] in ('ALLOWED', 'BANNED')
    assert advisory['supportStatus'] in ('SUPPORTED', 'UNSUPPORTED', 'REMOVED')
    assert advisory['status'] == ('BANNED' if advisory['securityStatus'] == 'BANNED' else advisory['supportStatus'])
    assert len(advisory['imageDigests']) == len(set(advisory['imageDigests']))
    assert set(advisory['bannedImageDigests']).issubset(advisory['imageDigests'])
if sys.argv[4]:
    banned=[item for item in payload['advisories']
            if item['pluginId'] == 'marketplace.reference' and item['version'] == sys.argv[4]]
    assert len(banned) == 1 and banned[0]['securityStatus'] == 'BANNED'
    assert banned[0]['bannedImageDigests'] == banned[0]['imageDigests']
with open(sys.argv[2], 'w', encoding='utf-8') as target:
    target.write(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':')))
with open(sys.argv[3], 'wb') as target:
    target.write(base64.urlsafe_b64decode(signature['value'] + '=' * (-len(signature['value']) % 4)))
PY
  if [[ -n ${ARTIO_MARKETPLACE_ADVISORY_TRUSTED_KEYS:-} ]]; then
    local trusted_id=${ARTIO_MARKETPLACE_ADVISORY_TRUSTED_KEYS%%=*}
    local trusted_key=${ARTIO_MARKETPLACE_ADVISORY_TRUSTED_KEYS#*=}
    local feed_key_id
    feed_key_id=$("$python" -c 'import json,sys;print(json.load(open(sys.argv[1]))["signature"]["keyId"])' "$package/advisories.json")
    [[ $trusted_id == "$feed_key_id" ]] || {
      echo 'Marketplace advisory key id did not match the independent trust configuration.' >&2
      return 1
    }
    "$python" - "$trusted_key" "$package/advisory-public-key.der" <<'PY'
import base64, sys
with open(sys.argv[2], 'wb') as target:
    target.write(base64.urlsafe_b64decode(sys.argv[1] + '=' * (-len(sys.argv[1]) % 4)))
PY
    openssl pkey -pubin -inform DER -in "$package/advisory-public-key.der" \
      -out "$package/advisory-public-key.pem" >/dev/null 2>&1
    openssl pkeyutl -verify -pubin -inkey "$package/advisory-public-key.pem" -rawin \
      -in "$package/advisory-payload.json" -sigfile "$package/advisory-signature.bin" >/dev/null
  fi
}
"$python" - <<'PY'
import json
with open('deployment/zot/config.json', encoding='utf-8') as source:
    zot = json.load(source)
assert zot['storage']['gc'] is False, 'Zot automatic GC must remain disabled'
actions = [action for repo in zot['http']['accessControl']['repositories'].values()
           for policy in repo.get('policies', []) for action in policy.get('actions', [])]
assert 'delete' not in actions, 'publisher Zot policies must not grant delete'
repositories = zot['http']['accessControl']['repositories']
assert repositories['staging/**']['policies'][0]['users'] == ['publisher', 'promoter']
assert repositories['public/**']['anonymousPolicy'] == ['read']
assert repositories['public/**']['policies'][0]['users'] == ['promoter']
assert '**' not in repositories, 'Unmatched repositories must default deny'
PY

management_base=${MARKETPLACE_MANAGEMENT_URL:-http://127.0.0.1:${MARKETPLACE_MANAGEMENT_PORT:-8081}}
wait_ready() {
  for _ in $(seq 1 60); do
    if curl --fail --silent --max-time 3 "$management_base/actuator/health/readiness" >/dev/null; then return 0; fi
    sleep 2
  done
  echo 'Marketplace did not become ready; inspect Compose logs.' >&2
  return 1
}
wait_ready
echo 'Marketplace readiness passed.'

v2_status=$(curl --silent --output /dev/null --write-out '%{http_code}' "http://$registry_connect/v2/")
[[ $v2_status == 200 ]] || { echo "Anonymous Zot v2 check returned HTTP $v2_status" >&2; exit 1; }
publisher_v2_status=$(curl --silent --dump-header "$package/publisher-ping.headers" --output /dev/null --write-out '%{http_code}' "http://$publisher_registry_connect/v2/")
[[ $publisher_v2_status == 200 || $publisher_v2_status == 401 ]] || { echo "Publisher Zot v2 ping returned HTTP $publisher_v2_status" >&2; exit 1; }
grep -qi '^WWW-Authenticate: Basic' "$package/publisher-ping.headers" || { echo 'Publisher Zot endpoint did not advertise its Basic-auth challenge.' >&2; exit 1; }
write_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST "http://$registry_connect/v2/public/plugins/anonymous-write/blobs/uploads/")
[[ $write_status == 401 || $write_status == 403 ]] || { echo "Anonymous Zot write unexpectedly returned HTTP $write_status" >&2; exit 1; }
public_write_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST "http://$publisher_registry_connect/v2/public/plugins/anonymous-write/blobs/uploads/")
[[ $public_write_status == 401 || $public_write_status == 403 ]] || { echo "Publisher endpoint exposed public writes (HTTP $public_write_status)" >&2; exit 1; }
echo 'Zot public read-only ACL and anonymous write-denial checks passed.'

zot_password=${MARKETPLACE_ZOT_TEST_PASSWORD:?Set the test Zot password for Docker login}
printf '%s' "$zot_password" | docker login "$publisher_registry" --username publisher --password-stdin >/dev/null
promotion_password=${MARKETPLACE_ZOT_PROMOTION_TEST_PASSWORD:?Set the isolated Zot promoter test password}
[[ $promotion_password != "$zot_password" ]] || { echo 'Publisher and promotion credentials must be distinct.' >&2; exit 1; }
export MARKETPLACE_PUBLISHER_TOKEN_FILE
accept='application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json'

resolve_digest() {
  local repository=$1 tag=$2
  curl --fail --silent --show-error --head --user "publisher:$zot_password" --header "Accept: $accept" \
    "http://$publisher_registry_connect/v2/$repository/manifests/$tag" |
    awk 'BEGIN{IGNORECASE=1} /^Docker-Content-Digest:/ {gsub("\r", ""); print $2}'
}

for version in 1.2.0 1.3.0 1.2.9 1.4.0-rc.1; do
  runtime_version=1
  [[ $version == 1.3.0 ]] && runtime_version=2
  main_tag="$publisher_registry/staging/plugins/artio-reference:$version"
  sidecar_tag="$publisher_registry/staging/plugins/artio-reference-companion:$version"
  tags+=("$main_tag" "$sidecar_tag")
  if [[ $version == 1.3.0 ]]; then
    # Exercise Zot promotion of an OCI index and both child platform manifests.
    main_tag_amd64="$publisher_registry/staging/plugins/artio-reference:$version-amd64"
    main_tag_arm64="$publisher_registry/staging/plugins/artio-reference:$version-arm64"
    tags+=("$main_tag_amd64" "$main_tag_arm64")
    docker build --platform linux/amd64 --build-arg "ARTIO_REFERENCE_VERSION=$runtime_version" -t "$main_tag_amd64" "$example"
    docker build --platform linux/arm64 --build-arg "ARTIO_REFERENCE_VERSION=$runtime_version" -t "$main_tag_arm64" "$example"
    docker push "$main_tag_amd64" >/dev/null
    docker push "$main_tag_arm64" >/dev/null
    docker manifest create --insecure "$main_tag" "$main_tag_amd64" "$main_tag_arm64" >/dev/null
    docker manifest push --insecure --purge "$main_tag" >/dev/null
    docker build --platform linux/amd64 --build-arg "ARTIO_REFERENCE_VERSION=$runtime_version" -t "$sidecar_tag" "$example"
  else
    docker build --platform linux/amd64 --build-arg "ARTIO_REFERENCE_VERSION=$runtime_version" -t "$main_tag" "$example"
    docker build --platform linux/amd64 --build-arg "ARTIO_REFERENCE_VERSION=$runtime_version" -t "$sidecar_tag" "$example"
    docker push "$main_tag" >/dev/null
  fi
  docker push "$sidecar_tag" >/dev/null

  main_digest=$(resolve_digest staging/plugins/artio-reference "$version")
  sidecar_digest=$(resolve_digest staging/plugins/artio-reference-companion "$version")
  [[ $main_digest =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "Missing main image digest for $version." >&2; exit 1; }
  [[ $sidecar_digest =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "Missing sidecar image digest for $version." >&2; exit 1; }
  if [[ $version == 1.2.0 ]]; then
    delete_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -u "publisher:$zot_password" -X DELETE \
      "http://$publisher_registry_connect/v2/staging/plugins/artio-reference/manifests/$main_digest")
    [[ $delete_status == 403 || $delete_status == 405 ]] || { echo "Zot publisher delete was not denied (HTTP $delete_status)." >&2; exit 1; }
  fi
  image_repositories=(artio-reference artio-reference-companion)
  image_digests=("$main_digest" "$sidecar_digest")
  for image_index in 0 1; do
    image_repository=${image_repositories[$image_index]}
    image_digest=${image_digests[$image_index]}
    staged_manifest=$(curl --fail --silent --show-error --user "publisher:$zot_password" --header "Accept: $accept" \
      "http://$publisher_registry_connect/v2/staging/plugins/$image_repository/manifests/$image_digest")
    for endpoint in "$registry_connect" "$publisher_registry_connect"; do
      staged_tag_status=$(curl --silent --output /dev/null --write-out '%{http_code}' --head --header "Accept: $accept" \
        "http://$endpoint/v2/staging/plugins/$image_repository/manifests/$version")
      [[ $staged_tag_status != 200 ]] || { echo "Private staging tag was anonymously readable on $endpoint." >&2; exit 1; }
      preapproval_status=$(curl --silent --output /dev/null --write-out '%{http_code}' --head --header "Accept: $accept" \
        "http://$endpoint/v2/public/plugins/$image_repository/manifests/$image_digest")
      if [[ $preapproval_status == 200 ]]; then
        previously_approved_digest=""
        [[ $image_repository == artio-reference ]] && previously_approved_digest=$main_digest_120
        [[ $image_repository == artio-reference-companion ]] && previously_approved_digest=$sidecar_digest_120
        [[ -n $previously_approved_digest && $image_digest == "$previously_approved_digest" ]] || {
          echo "A new unapproved OCI digest was publicly readable on $endpoint." >&2
          exit 1
        }
      fi
      # Blob digests can be shared with a previously approved release in the same public
      # repository. Check blob confidentiality before the first promotion, while the
      # isolated public namespace is still empty; later releases still check manifest ACLs.
      if [[ $version == 1.2.0 ]]; then
        for blob_digest in $("$python" -c 'import json,sys; m=json.load(sys.stdin); print(m["config"]["digest"], *(x["digest"] for x in m["layers"]))' <<<"$staged_manifest"); do
          blob_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
            "http://$endpoint/v2/public/plugins/$image_repository/blobs/$blob_digest")
          if [[ $blob_status == 200 ]]; then
            [[ -n $previously_approved_digest && $image_digest == "$previously_approved_digest" ]] || {
              echo "A blob from a new unapproved OCI digest was anonymously readable on $endpoint." >&2
              exit 1
            }
          fi
        done
      fi
      private_read_status=$(curl --silent --output /dev/null --write-out '%{http_code}' --head --header "Accept: $accept" \
        "http://$endpoint/v2/staging/plugins/$image_repository/manifests/$image_digest")
      [[ $private_read_status != 200 ]] || { echo "Private staging digest was anonymously readable on $endpoint." >&2; exit 1; }
    done
  done
  publisher_public_write=$(curl --silent --output /dev/null --write-out '%{http_code}' --user "publisher:$zot_password" -X POST \
    "http://$publisher_registry_connect/v2/public/plugins/publisher-write-check/blobs/uploads/")
  [[ $publisher_public_write == 401 || $publisher_public_write == 403 ]] || { echo "Publisher can write public OCI namespace (HTTP $publisher_public_write)." >&2; exit 1; }
  main_ref="$registry/public/plugins/artio-reference@$main_digest"
  sidecar_ref="$registry/public/plugins/artio-reference-companion@$sidecar_digest"
  tags+=("$main_ref" "$sidecar_ref")
  artifact_refs+=("$main_ref" "$sidecar_ref")
  release_refs+=("$main_ref")

  release_dir="$package/$version"
  mkdir -p "$release_dir/media"
  maximum_core=9.9.9
  [[ $version == 1.2.9 ]] && maximum_core=0.5.0
  if [[ $version == 1.3.0 ]]; then
    cat >"$release_dir/manifest.json" <<JSON
{
  "packageManifest": {
    "manifestVersion":"2",
    "id":"marketplace.reference",
    "name":"Artio Reference Extension",
    "vendor":"Artio Marketplace",
    "version":"$version",
    "description":"Runnable v2 reference extension for validating Marketplace distribution with the Artio Core runtime.",
    "minimumCoreVersion":"0.1.0",
    "maximumCoreVersion":"$maximum_core",
    "image":"$main_ref",
    "dependencies":[],"permissions":[],"configuration":{},"extensionPoints":[],
    "healthPath":"/health","diagnosticsPath":"/diagnostics",
    "geographicAvailability":{"scope":"GLOBAL","countries":[]},
    "services":[{"id":"companion","image":"$sidecar_ref","port":8080,"healthPath":"/health","dependsOn":["main"]}],
    "persistentPaths":["/var/lib/artio-reference"]
  },
  "capabilities":[{"capabilityId":"payment.provider.v1","operations":["createCheckout"],"permissions":["payment.process"],"accountFingerprints":{}}],
  "subscriptions":[{"eventType":"order.paid","schemaVersion":1,"permissions":[]}]
}
JSON
  else
    cat >"$release_dir/manifest.json" <<JSON
{
  "manifestVersion":"1",
  "id":"marketplace.reference",
  "name":"Artio Reference Extension",
  "vendor":"Artio Marketplace",
  "version":"$version",
  "description":"Runnable reference extension for validating Marketplace distribution with the Artio Core runtime.",
  "minimumCoreVersion":"0.1.0",
  "maximumCoreVersion":"$maximum_core",
  "image":"$main_ref",
  "dependencies":[],"permissions":["payment.process"],"configuration":{},"extensionPoints":["payment.provider.v1"],
  "healthPath":"/health","diagnosticsPath":"/diagnostics",
  "geographicAvailability":{"scope":"GLOBAL","countries":[]},
  "services":[{"id":"companion","image":"$sidecar_ref","port":8080,"healthPath":"/health","dependsOn":["main"]}],
  "persistentPaths":["/var/lib/artio-reference"]
}
JSON
  fi
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
  publish_output=$(./scripts/publish-plugin.sh "$release_dir" "$base")
  printf '%s\n' "$publish_output"
  ./scripts/publish-plugin.sh "$release_dir" "$base"
  staged_release_id=$(sed -n 's/^releaseId=//p' <<<"$publish_output" | tail -n 1)
  "$python" - "$base" "$staged_release_id" "$publisher_token" "$admin_token" "$version" <<'PY'
import json, sys, urllib.error, urllib.request
base, release_id, publisher, admin, version = sys.argv[1:]
root = '/api/v1/admin/marketplace/releases/' + release_id
def request(path, method='GET', token=publisher, payload=None):
    headers = {} if token is None else {'Authorization': 'Bearer ' + token}
    data = None if payload is None else json.dumps(payload).encode()
    if data is not None: headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None
status, review = request(root + '/review', token=admin)
assert status == 200 and review['approvalState'] == 'PENDING', 'Admin could not inspect staged release'
status, _ = request('/api/v1/marketplace/plugins/marketplace.reference/releases/' + version + '/manifest', token=None)
assert status == 404, f'Unapproved descriptor was available with HTTP {status}'
status, _ = request(root + '/approve', 'POST', payload={'reason': 'publisher must not self-approve'})
assert status == 401, f'Publisher approval returned HTTP {status}'
status, _ = request(root + '/publish', 'POST', token=admin)
assert status == 401, f'Admin-only publish returned HTTP {status}'
status, _ = request(root + '/publish', 'POST')
assert status == 409, f'Unapproved release publish returned HTTP {status}'
status, approved = request(root + '/approve', 'POST', token=admin,
                            payload={'reason': 'acceptance review of exact staged manifest and catalog'})
assert status == 200 and approved['approvalState'] == 'APPROVED', 'Admin approval failed'
status, _ = request(root + '/catalog', 'PUT', payload=review['catalog'])
assert status == 200, f'Catalog edit returned HTTP {status}'
status, _ = request(root + '/publish', 'POST')
assert status == 409, 'A catalog edit did not invalidate admin approval'
status, approved = request(root + '/approve', 'POST', token=admin,
                            payload={'reason': 're-review after catalog update'})
assert status == 200 and approved['approvalState'] == 'APPROVED', 'Re-approval failed'
status, _ = request('/api/v1/marketplace/plugins/marketplace.reference/releases/' + version + '/manifest', token=None)
assert status == 404, f'Approved but unpromoted descriptor was available with HTTP {status}'
PY
  MARKETPLACE_PUBLISHER_TOKEN_FILE="$MARKETPLACE_PUBLISHER_TOKEN_FILE" \
    ./scripts/publish-staged-release.sh "$staged_release_id" "$base" >/dev/null
  unsigned_descriptor_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "$base/api/v1/marketplace/plugins/marketplace.reference/releases/$version/descriptor")
  [[ $unsigned_descriptor_status == 404 ]] || { echo "Unsigned official descriptor was exposed (HTTP $unsigned_descriptor_status)." >&2; exit 1; }
  sign_release "$staged_release_id" "$version"
  postgres_container=$("${compose[@]}" ps -q postgres)
  promotion_state=$(docker exec "$postgres_container" psql -At -U artio_marketplace -d artio_marketplace -c \
    "SELECT state || ':' || attempt_count FROM publication_promotions WHERE release_id='$staged_release_id'")
  [[ $promotion_state == 'PUBLISHED:1' ]] || { echo "Durable promotion job state was $promotion_state for $version." >&2; exit 1; }
  if [[ $version == 1.2.0 ]]; then release_id_120=$(sed -n 's/^releaseId=//p' <<<"$publish_output" | tail -n 1); fi
  if [[ $version == 1.2.9 ]]; then release_id_129=$(sed -n 's/^releaseId=//p' <<<"$publish_output" | tail -n 1); fi
  if [[ $version == 1.2.0 ]]; then main_digest_120=$main_digest; sidecar_digest_120=$sidecar_digest; fi

  docker pull "$main_ref" >/dev/null
  docker pull "$sidecar_ref" >/dev/null
  if [[ $version == 1.3.0 ]]; then
    docker pull --platform linux/amd64 "$main_ref" >/dev/null
    # Docker's classic image store associates a selected platform with the index digest.
    docker image rm "$main_ref" >/dev/null
    docker pull --platform linux/arm64 "$main_ref" >/dev/null
    echo 'Zot promotion preserved the OCI index and both linux/amd64 and linux/arm64 manifests.'
  fi
  prerelease_query=""
  [[ $version == *-* ]] && prerelease_query='?includePrerelease=true'
  curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference/releases/$version/manifest$prerelease_query" >/dev/null
done
verify_advisory_feed
old_main_ref="$registry/public/plugins/artio-reference@$main_digest_120"
main_tag_120="$publisher_registry/staging/plugins/artio-reference:1.2.0"
overwrite_tag="$publisher_registry/staging/plugins/artio-reference:acceptance-overwrite"
tags+=("$overwrite_tag")
docker build --platform linux/amd64 --build-arg ARTIO_REFERENCE_VERSION=2 -t "$overwrite_tag" "$example"
docker push "$overwrite_tag" >/dev/null
docker tag "$overwrite_tag" "$main_tag_120"
docker push "$main_tag_120" >/dev/null
overwritten_digest=$(resolve_digest staging/plugins/artio-reference 1.2.0)
[[ $overwritten_digest =~ ^sha256:[a-f0-9]{64}$ && $overwritten_digest != "$main_digest_120" ]] || {
  echo 'Overwriting a Zot tag did not produce a new manifest digest.' >&2
  exit 1
}
curl --fail --silent --show-error --head --user "publisher:$zot_password" --header "Accept: $accept" \
  "http://$publisher_registry_connect/v2/staging/plugins/artio-reference/manifests/$main_digest_120" >/dev/null
docker image rm "$old_main_ref" >/dev/null
docker pull "$old_main_ref" >/dev/null
echo 'Zot retained the previous manifest and image after its mutable tag was overwritten.'
echo 'OCI pushes and release publication loop completed.'

docker logout "$publisher_registry" >/dev/null 2>&1 || true
"${compose[@]}" restart marketplace
wait_ready
for version in 1.2.0 1.3.0; do
  curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference/releases/$version/manifest" >/dev/null
done
curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference?locale=de" | grep -q 'Artio Referenzerweiterung'
"$python" - "$base" <<'PY'
import json, sys, urllib.request
base = sys.argv[1]
def get(path):
    with urllib.request.urlopen(base + path) as response:
        return json.load(response)
assert get('/api/v1/marketplace/plugins/marketplace.reference')['version'] == '1.3.0'
releases = get('/api/v1/marketplace/plugins/marketplace.reference/releases?size=10')
assert [r['version'] for r in releases['content']] == ['1.3.0', '1.2.9', '1.2.0']
preview = get('/api/v1/marketplace/plugins/marketplace.reference/releases?includePrerelease=true&size=10')
assert [r['version'] for r in preview['content']] == ['1.4.0-rc.1', '1.3.0', '1.2.9', '1.2.0']
compatible = get('/api/v1/marketplace/plugins/marketplace.reference/releases?coreVersion=1.0.0&size=10')
assert [r['version'] for r in compatible['content']] == ['1.3.0', '1.2.0']
assert get('/api/v1/marketplace/plugins/marketplace.reference/latest-compatible?coreVersion=1.0.0')['version'] == '1.3.0'
assert get('/api/v1/marketplace/plugins/marketplace.reference/latest-compatible?coreVersion=1.0.0&includePrerelease=true')['version'] == '1.4.0-rc.1'
filtered = get('/api/v1/marketplace/plugins?category=other&country=CH&coreVersion=1.0.0&size=1')
assert filtered['totalElements'] == 1 and filtered['content'][0]['version'] == '1.3.0'
assert get('/api/v1/marketplace/plugins?category=payments')['totalElements'] == 0
searched = get('/api/v1/marketplace/plugins?q=marketplace.reference&category=other&size=1')
assert searched['totalElements'] == 1 and searched['content'][0]['pluginId'] == 'marketplace.reference'
preview_card = get('/api/v1/marketplace/plugins?category=other&coreVersion=1.0.0&includePrerelease=true&size=1')
assert preview_card['content'][0]['version'] == '1.4.0-rc.1'
PY
echo 'SemVer release selection checks passed.'
revocation_token=$(cat "$MARKETPLACE_REVOCATION_TOKEN_FILE")
"$python" - "$base" "$publisher_token" "$admin_token" "$release_id_120" <<'PY'
import json, sys, urllib.error, urllib.request
base, publisher, admin, release_id = sys.argv[1:]
def request(path, method='GET', token=publisher, payload=None):
    headers = {'Authorization': 'Bearer ' + token}
    data = None if payload is None else json.dumps(payload).encode()
    if data is not None: headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as response: return response.status, json.load(response)
    except urllib.error.HTTPError as error: return error.code, None
status, draft = request('/api/v1/admin/marketplace/releases/' + release_id)
assert status == 200
status, audit_before = request('/api/v1/admin/marketplace/publication-audit?size=100', token=admin)
assert status == 200
draft['catalog']['title']['en'] = 'Mutation must fail after publication'
status, _ = request('/api/v1/admin/marketplace/releases/' + release_id + '/catalog', 'PUT', payload=draft['catalog'])
assert status == 409, f'Immutable catalog update returned HTTP {status}'
status, audit = request('/api/v1/admin/marketplace/publication-audit?size=100', token=admin)
assert status == 200
assert len(audit) == len(audit_before), 'Rejected immutable catalog update wrote an audit record'
PY
echo 'Immutable catalog update/CAS and role separation checks passed.'
wrong_role_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
  -H "Authorization: Bearer $publisher_token" -H 'Content-Type: application/json' \
  --data '{"state":"SUSPENDED","reason":"role isolation check"}' \
  "$base/api/v1/admin/marketplace/plugins/marketplace.reference/moderation")
[[ $wrong_role_status == 401 ]] || { echo 'Publisher credential was accepted for moderation.' >&2; exit 1; }
curl --fail --silent -X POST -H "Authorization: Bearer $admin_token" -H 'Content-Type: application/json' \
  --data '{"state":"SUSPENDED","reason":"acceptance moderation check"}' \
  "$base/api/v1/admin/marketplace/plugins/marketplace.reference/moderation" >/dev/null
suspended_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "$base/api/v1/marketplace/plugins/marketplace.reference/latest-compatible?coreVersion=1.0.0")
[[ $suspended_status == 404 ]] || { echo 'Suspended plugin remained recommended.' >&2; exit 1; }
"$python" - "$base" "$publisher_token" "$admin_token" "$release_id_120" <<'PY'
import json, sys, urllib.error, urllib.request
base, token, admin, source_id = sys.argv[1:]
def request(path, method='GET', payload=None, key=None, credential=token):
    headers = {'Authorization': 'Bearer ' + credential}
    if key: headers['Idempotency-Key'] = key
    data = None if payload is None else json.dumps(payload).encode()
    if data is not None: headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None
status, source = request('/api/v1/admin/marketplace/releases/' + source_id)
assert status == 200
manifest = source['manifest']
catalog = source['catalog']
runtime_manifest = manifest.get('packageManifest', manifest)
runtime_manifest['version'] = '9.0.0'
catalog['releaseNotes']['en'] = 'Suspended publication rejection check.'
status, created = request('/api/v1/admin/marketplace/releases', 'POST',
                          {'manifest': manifest, 'catalog': catalog}, 'suspended-publish-9.0.0')
assert status == 201, f'Could not reserve suspended release check: HTTP {status}'
status, _ = request('/api/v1/admin/marketplace/releases/' + created['releaseId'] + '/approve', 'POST',
                    {'reason': 'suspended plugin must block release approval'}, credential=admin)
assert status == 409, f'Suspended plugin approval returned HTTP {status}'
status, _ = request('/api/v1/admin/marketplace/releases/' + created['releaseId'] + '/publish', 'POST')
assert status == 409, f'Suspended plugin publication returned HTTP {status}'
PY
curl --fail --silent -X POST -H "Authorization: Bearer $admin_token" -H 'Content-Type: application/json' \
  --data '{"state":"ACTIVE","reason":"acceptance reactivation check"}' \
  "$base/api/v1/admin/marketplace/plugins/marketplace.reference/moderation" >/dev/null
if [[ -n $release_id_129 ]]; then
  admin_revoke_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
    -H "Authorization: Bearer $admin_token" -H 'Content-Type: application/json' \
    --data '{"category":"SECURITY","reason":"wrong role"}' "$base/api/v1/admin/marketplace/releases/$release_id_129/revoke")
  [[ $admin_revoke_status == 401 ]] || { echo 'Admin credential was accepted for release revocation.' >&2; exit 1; }
  curl --fail --silent -X POST -H "Authorization: Bearer $revocation_token" -H 'Content-Type: application/json' \
    --data '{"category":"SECURITY","reason":"acceptance release scoped revocation check"}' \
    "$base/api/v1/admin/marketplace/releases/$release_id_129/revoke" >/dev/null
  revoked_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "$base/api/v1/marketplace/plugins/marketplace.reference/releases/1.2.9/manifest")
  [[ $revoked_status == 410 ]] || { echo 'Revoked release did not return HTTP 410.' >&2; exit 1; }
  [[ $(curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference/latest-compatible?coreVersion=1.0.0" | "$python" -c 'import json,sys;print(json.load(sys.stdin)["version"])') == 1.3.0 ]] || { echo 'Revoking an older release affected the latest release.' >&2; exit 1; }
  verify_advisory_feed 1.2.9
fi
echo 'Moderation, release-scoped revocation and catalog checks passed.'
if ((${#tags[@]})); then docker image rm "${tags[@]}" >/dev/null 2>&1 || true; fi
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
"${compose[@]}" stop marketplace >/dev/null
offline_catalog_status=$(curl --silent --output /dev/null --write-out '%{http_code}' "$base/api/v1/marketplace/categories" || true)
[[ $offline_catalog_status != 200 ]] || { echo 'Marketplace API remained available after its service container was stopped.' >&2; exit 1; }
curl --fail --silent --show-error "http://127.0.0.1:$smoke_port/health" >/dev/null
offline_response=$(curl --fail --silent --show-error "http://127.0.0.1:$smoke_port/v1/invoke" "${invoke_headers[@]}" --data "$invoke")
[[ $offline_response == "$first_response" ]] || { echo 'Installed reference plugin did not keep serving invocation while Marketplace was offline.' >&2; exit 1; }
"${compose[@]}" start marketplace >/dev/null
wait_ready
docker rm --force "$smoke_container" >/dev/null
smoke_container=""
curl --fail --silent "$base/api/v1/marketplace/plugins/marketplace.reference?locale=de" | grep -q 'Artio Referenzerweiterung'
curl --fail --silent "$base/api/v1/marketplace/categories?locale=fr" | grep -q 'Autre'

cat >"$package/proxy.conf" <<'NGINX'
server {
    listen 8088;
    location ^~ /actuator/ { return 404; }
    location ^~ /api/v1/admin/ {
        allow 203.0.113.0/24;
        deny all;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_pass http://marketplace:8080;
    }
    location / {
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_pass http://marketplace:8080;
    }
}
NGINX
proxy_container="marketplace-proxy-smoke-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
project=${MARKETPLACE_COMPOSE_PROJECT:-deployment}
proxy_config_path="$package/proxy.conf"
if command -v cygpath >/dev/null 2>&1; then proxy_config_path=$(cygpath -m "$proxy_config_path"); fi
MSYS_NO_PATHCONV=1 docker run --detach --name "$proxy_container" --network "${project}_default" --publish '127.0.0.1::8088' \
  --user 101:101 --read-only \
  --tmpfs /var/cache/nginx:rw,noexec,nosuid,size=8m,uid=101,gid=101,mode=0755 \
  --tmpfs /var/run:rw,noexec,nosuid,size=1m,uid=101,gid=101,mode=0755 \
  --security-opt no-new-privileges:true --cap-drop ALL \
  --volume "$proxy_config_path:/etc/nginx/conf.d/default.conf:ro" \
  nginx:1.29-alpine@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de >/dev/null
proxy_port=$(docker port "$proxy_container" 8088/tcp | sed 's/.*://')
for _ in $(seq 1 20); do
  proxy_public_status=$(curl --silent --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:$proxy_port/api/v1/marketplace/categories")
  [[ $proxy_public_status == 200 ]] && break
  sleep 1
done
[[ $proxy_public_status == 200 ]] || { echo 'Public API proxy smoke test failed.' >&2; exit 1; }
proxy_admin_status=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
  "http://127.0.0.1:$proxy_port/api/v1/admin/marketplace/releases/validate")
[[ $proxy_admin_status == 403 ]] || { echo "Admin network boundary returned HTTP $proxy_admin_status." >&2; exit 1; }
proxy_actuator_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "http://127.0.0.1:$proxy_port/actuator/health/readiness")
[[ $proxy_actuator_status == 404 ]] || { echo "Actuator proxy boundary returned HTTP $proxy_actuator_status." >&2; exit 1; }
for actuator_path in prometheus env mappings configprops heapdump logfile metrics; do
  actuator_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "$management_base/actuator/$actuator_path")
  [[ $actuator_status == 403 || $actuator_status == 404 ]] || {
    echo "Direct management endpoint $actuator_path returned HTTP $actuator_status." >&2
    exit 1
  }
done
echo 'Reverse proxy public, admin-network and actuator checks passed.'
docker rm --force "$proxy_container" >/dev/null
proxy_container=""

core_status=0
if [[ -n ${ARTIO_CORE_PATH:-} ]]; then
  core_path=$(cd "$ARTIO_CORE_PATH" && pwd)
  export ARTIO_TEST_EXTENSION_IMAGE_V1=${release_refs[0]}
  export ARTIO_TEST_EXTENSION_IMAGE_V2=${release_refs[1]}
  export ARTIO_MARKETPLACE_BASE_URL=${ARTIO_MARKETPLACE_BASE_URL:-$base}
  export ARTIO_MARKETPLACE_TEST_PLUGIN_ID=marketplace.reference
  export ARTIO_MARKETPLACE_TEST_PLUGIN_VERSION=1.3.0
  export ARTIO_MARKETPLACE_EXPECTED_BANNED_PLUGIN_ID=marketplace.reference
  export ARTIO_MARKETPLACE_EXPECTED_BANNED_PLUGIN_VERSION=1.2.9
  signing_public_key=$(openssl pkey -in "$MARKETPLACE_SIGNING_PRIVATE_KEY_FILE" -pubout -outform DER | base64 -w0 | tr '+/' '-_' | tr -d '=')
  export ARTIO_MARKETPLACE_TRUSTED_KEYS="$MARKETPLACE_SIGNING_KEY_ID=$signing_public_key"
  export ARTIO_EXTENSION_TRUSTED_REGISTRIES=${ARTIO_EXTENSION_TRUSTED_REGISTRIES:-$registry}
  if (cd "$core_path" && bash ./gradlew --no-daemon :artio-rest-api:integrationTest --rerun \
    --tests io.artio.api.extensions.DockerExtensionRuntimeIntegrationTests \
    --tests io.artio.api.catalog.CatalogApiIntegrationTests.extensionLifecycleEnforcesDependencyStateAndVersionBeforeRuntimeCalls \
    --tests io.artio.api.catalog.CatalogApiIntegrationTests.importsPublishedMarketplaceManifestThroughCoreApiWhenAcceptanceIsConfigured \
    --tests io.artio.api.extensions.MarketplaceAdvisoryAcceptanceIntegrationTests.consumesAndPersistsTheLiveMarketplaceSecurityRevocation); then
    import_report="$core_path/artio-rest-api/build/test-results/integrationTest/TEST-io.artio.api.catalog.CatalogApiIntegrationTests.xml"
    advisory_report="$core_path/artio-rest-api/build/test-results/integrationTest/TEST-io.artio.api.extensions.MarketplaceAdvisoryAcceptanceIntegrationTests.xml"
    if [[ ! -f $import_report ]] || ! grep -q 'importsPublishedMarketplaceManifestThroughCoreApiWhenAcceptanceIsConfigured' "$import_report" \
      || [[ ! -f $advisory_report ]] || ! grep -q 'consumesAndPersistsTheLiveMarketplaceSecurityRevocation' "$advisory_report" \
      || grep -q '<skipped\|<failure\|<error' "$advisory_report"; then
      core_status=1
      echo 'The pinned Core checkout did not pass both required Marketplace manifest import and signed security advisory acceptance tests.' >&2
    else
      echo 'Core Docker runtime, Marketplace manifest import, live signed advisory ingestion and exact digest ban persistence passed against Marketplace-published artifacts.'
    fi
  else
    core_status=$?
    advisory_report="$core_path/artio-rest-api/build/test-results/integrationTest/TEST-io.artio.api.extensions.MarketplaceAdvisoryAcceptanceIntegrationTests.xml"
    if [[ -f $advisory_report ]]; then
      "$python" - "$advisory_report" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for failure in root.iter('failure'):
    print('Marketplace advisory Core acceptance failure: ' + (failure.get('message') or failure.text or 'unknown failure'), file=sys.stderr)
PY
    fi
    echo "Pinned Core compatibility checks failed with exit $core_status; inspect the reported Core test failures. This step does not modify Artio Core." >&2
  fi
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

echo 'Docker acceptance passed: anonymous pulls and denied writes, authenticated OCI pushes, old-digest retention after tag overwrite, digest-pinned main/sidecar artifacts, SemVer/Core/country/category/search filtering, separate approval roles, signed advisory feed verification and exact-digest security revocation, localized catalog, manifest retrieval, a restricted plugin invocation smoke test, runtime availability during Marketplace outage, and bounded public API rate limiting. Test-local containers, images and files are removed by the exit trap.'
if ((core_status != 0)); then exit "$core_status"; fi
