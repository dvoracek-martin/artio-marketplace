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

api_base=${MARKETPLACE_API_BASE:-http://127.0.0.1:${MARKETPLACE_API_PORT:-8080}}
wait_for() {
  local tries=0
  until curl --fail --silent "$api_base/actuator/health/readiness" >/dev/null; do
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
before_manifest=$(curl --fail --silent --show-error "$manifest_url")
before_image=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["image"])' <<<"$before_manifest")

# Stop the API/Zot container to freeze catalog, media and registry writes while
# the logical PostgreSQL dump and file-volume archives are captured.
"${compose[@]}" stop marketplace >/dev/null
"${compose[@]}" exec -T postgres pg_dump -U artio_marketplace -Fc artio_marketplace >"$backup_dir/marketplace.pgdump"

for volume in zot_data catalog_assets; do
  docker run --rm --user 0 --entrypoint tar \
    --volume "${project}_${volume}:/source:ro" \
    --volume "$backup_dir:/backup" \
    artio-marketplace:local -czf "/backup/${volume}.tar.gz" -C /source .
  docker run --rm --user 0 --entrypoint tar \
    --volume "$backup_dir:/backup:ro" \
    artio-marketplace:local -tzf "/backup/${volume}.tar.gz" >/dev/null
done

test -s "$backup_dir/marketplace.pgdump"
echo 'Backup captured; simulating loss of the isolated database, Zot and media volumes.'
"${compose[@]}" down --remove-orphans --volumes

for volume in zot_data catalog_assets; do
  docker volume create "${project}_${volume}" >/dev/null
  docker run --rm --user 0 --entrypoint tar \
    --volume "${project}_${volume}:/target" \
    --volume "$backup_dir:/backup:ro" \
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
after_image=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["image"])' <<<"$after_manifest")
[[ $after_image == "$before_image" ]] || { echo 'The restored manifest points at a different image digest.' >&2; exit 1; }
docker pull "$after_image"
curl --fail --silent --show-error "$api_base/api/v1/marketplace/plugins/marketplace.reference?locale=en" | grep -q 'Artio Reference Extension'

echo 'Backup/restore drill passed: fresh PostgreSQL, Zot and media volumes restored; published manifest remained byte-for-byte identical and its OCI digest pulled successfully.'
