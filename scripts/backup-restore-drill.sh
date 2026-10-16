#!/usr/bin/env bash
set -euo pipefail

if [[ ${MARKETPLACE_RESTORE_DRILL:-} != 1 ]]; then
  echo 'This script destroys and recreates its named Compose volumes. Set MARKETPLACE_RESTORE_DRILL=1 only for an isolated acceptance stack.' >&2
  exit 2
fi

project=${MARKETPLACE_COMPOSE_PROJECT:-}
if [[ ! $project =~ ^[a-z0-9][a-z0-9_-]{0,50}$ ]]; then
  echo 'Set MARKETPLACE_COMPOSE_PROJECT to the isolated Compose project name.' >&2
  exit 2
fi

env_file=${MARKETPLACE_ENV_FILE:-.env}
compose=(docker compose --project-name "$project" --env-file "$env_file" -f deployment/compose.yaml)
backup_dir=$(mktemp -d "${TMPDIR:-/tmp}/artio-marketplace-restore.XXXXXX")
cleanup() { rm -rf -- "$backup_dir"; }
trap cleanup EXIT
backup_mount=$(if command -v cygpath >/dev/null 2>&1; then cygpath -m "$backup_dir"; else printf '%s' "$backup_dir"; fi)

api_base=${MARKETPLACE_API_BASE:-http://127.0.0.1:${MARKETPLACE_API_PORT:-8080}}
management_base=${MARKETPLACE_MANAGEMENT_URL:-http://127.0.0.1:${MARKETPLACE_MANAGEMENT_PORT:-8081}}
wait_for() {
  local tries=0
  until curl --fail --silent "$management_base/actuator/health/readiness" >/dev/null; do
    tries=$((tries + 1))
    if (( tries >= 60 )); then
      echo 'Marketplace did not become ready during the restore drill.' >&2
      return 1
    fi
    sleep 2
  done
}

echo "Capturing a consistent point-in-time set for Compose project $project."
# The preceding acceptance intentionally exhausts the public API rate limit.
# Restart this disposable API process so the recovery probe starts in a clean
# rate-limit window; PostgreSQL, Zot and media volumes remain untouched.
"${compose[@]}" restart marketplace >/dev/null
wait_for
manifest_url="$api_base/api/v1/marketplace/plugins/marketplace.reference/releases/1.2.0/manifest"
descriptor_url="$api_base/api/v1/marketplace/plugins/marketplace.reference/releases/1.2.0/descriptor"
advisories_url="$api_base/api/v1/marketplace/security/advisories"
before_manifest=$(curl --fail --silent --show-error "$manifest_url")
before_image=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["image"])' <<<"$before_manifest")
curl --fail --silent --show-error "$descriptor_url" -o "$backup_dir/release-descriptor.before.json"
curl --fail --silent --show-error "$advisories_url" -o "$backup_dir/advisories.before.json"

# Stop the API/Zot container to freeze catalog, media and registry writes while
# the logical PostgreSQL dump and file-volume archives are captured.
"${compose[@]}" stop marketplace >/dev/null
"${compose[@]}" exec -T postgres pg_dump -U artio_marketplace -Fc artio_marketplace >"$backup_dir/marketplace.pgdump"

for volume in zot_data catalog_assets; do
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 --entrypoint tar \
    --volume "${project}_${volume}:/source:ro" \
    --volume "$backup_mount:/backup" \
    artio-marketplace:local -czf "/backup/${volume}.tar.gz" -C /source .
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 --entrypoint tar \
    --volume "$backup_mount:/backup:ro" \
    artio-marketplace:local -tzf "/backup/${volume}.tar.gz" >/dev/null
done

test -s "$backup_dir/marketplace.pgdump"
echo 'Backup captured; simulating loss of the isolated database, Zot and media volumes.'
"${compose[@]}" down --remove-orphans --volumes

for volume in zot_data catalog_assets; do
  docker volume create "${project}_${volume}" >/dev/null
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 --entrypoint tar \
    --volume "${project}_${volume}:/target" \
    --volume "$backup_mount:/backup:ro" \
    artio-marketplace:local --numeric-owner -xzf "/backup/${volume}.tar.gz" -C /target
done

# Compose creates a clean PostgreSQL volume. Restore the logical dump before
# starting the Marketplace service so Flyway sees the backed-up schema/data.
"${compose[@]}" up -d postgres
for attempt in $(seq 1 60); do
  if "${compose[@]}" exec -T postgres pg_isready -U artio_marketplace -d artio_marketplace >/dev/null 2>&1; then
    break
  fi
  if (( attempt == 60 )); then
    echo 'PostgreSQL did not become ready during the restore drill.' >&2
    exit 1
  fi
  sleep 2
done
cat "$backup_dir/marketplace.pgdump" | "${compose[@]}" exec -T postgres pg_restore --exit-on-error -U artio_marketplace -d artio_marketplace
"${compose[@]}" up -d marketplace registry-gateway
wait_for

after_manifest=$(curl --fail --silent --show-error "$manifest_url")
[[ $after_manifest == "$before_manifest" ]] || {
  echo 'The immutable published manifest changed across the restore drill.' >&2
  exit 1
}
curl --fail --silent --show-error "$descriptor_url" -o "$backup_dir/release-descriptor.after.json"
curl --fail --silent --show-error "$advisories_url" -o "$backup_dir/advisories.after.json"
python3 - "$backup_dir/release-descriptor.before.json" "$backup_dir/release-descriptor.after.json" \
  "$backup_dir/advisories.before.json" "$backup_dir/advisories.after.json" <<'PY'
import json, sys

def load(path):
    with open(path, encoding='utf-8') as source:
        return json.load(source)

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

release_before, release_after, feed_before, feed_after = map(load, sys.argv[1:])
assert release_before['signature']['algorithm'] == 'Ed25519'
assert release_before['signature']['keyId'] == release_after['signature']['keyId']
assert release_before['descriptor'] == release_after['descriptor']
assert release_before['signature'] == release_after['signature'], 'Stored release signature changed after restore'
assert feed_before['signature']['algorithm'] == 'Ed25519'
assert feed_before['signature']['keyId'] == feed_before['payload']['keyId']
assert feed_before['signature'] == feed_after['signature'], 'Advisory signature changed after restore'
assert feed_before['payload'] == feed_after['payload'], 'Signed advisory payload changed after restore'
assert canonical(release_before) == canonical(release_after)
assert canonical(feed_before) == canonical(feed_after)
PY
after_image=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["image"])' <<<"$after_manifest")
[[ $after_image == "$before_image" ]] || { echo 'The restored manifest points at a different image digest.' >&2; exit 1; }
docker pull "$after_image"
curl --fail --silent --show-error "$api_base/api/v1/marketplace/plugins/marketplace.reference?locale=en" | grep -q 'Artio Reference Extension'

echo 'Backup/restore drill passed: fresh PostgreSQL, Zot and media volumes restored; published manifest, signed release descriptor and signed advisory snapshot were identical, and the OCI digest pulled successfully.'
