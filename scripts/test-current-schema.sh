#!/usr/bin/env bash
set -euo pipefail
export MSYS_NO_PATHCONV=1

container="marketplace-current-schema-${GITHUB_RUN_ID:-local}-$$"
image='postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea'
cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "Current-schema migration test failed (exit $status); PostgreSQL container diagnostics follow." >&2
    docker inspect --format 'state={{.State.Status}} exit={{.State.ExitCode}} error={{.State.Error}}' "$container" >&2 || true
    docker logs "$container" >&2 || true
  fi
  docker rm --force "$container" >/dev/null 2>&1 || true
  return "$status"
}
trap cleanup EXIT

docker run --detach --name "$container" --tmpfs /var/lib/postgresql/data:rw,size=256m \
  --env POSTGRES_PASSWORD=schema-test-only "$image" >/dev/null
database_ready=0
for _ in $(seq 1 60); do
  if docker exec "$container" psql -U postgres -d postgres -Atqc 'SELECT 1' >/dev/null 2>&1; then
    database_ready=1
    break
  fi
  sleep 1
done
if [[ "$database_ready" != 1 ]]; then
  echo 'Disposable PostgreSQL did not become queryable within 60 seconds.' >&2
  exit 1
fi
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
  < marketplace-infrastructure/src/main/resources/db/migration/V1__marketplace.sql

# Add pre-existing content before the forward migration and confirm V2 preserves it.
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
INSERT INTO marketplace_plugins(plugin_id,slug,vendor) VALUES ('migration.fixture','migration-fixture','Migration fixture');
INSERT INTO marketplace_releases(release_id,plugin_id,version,manifest,manifest_document,manifest_sha256,metadata,publication_state,create_request_sha256,published_at)
VALUES ('11111111-1111-4111-8111-111111111111','migration.fixture','1.0.0','{}'::jsonb,'{}','0000000000000000000000000000000000000000000000000000000000000000','{}'::jsonb,'PUBLISHED','0000000000000000000000000000000000000000000000000000000000000000',now());
SQL
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
  < marketplace-infrastructure/src/main/resources/db/migration/V2__local_dev_admin_imports_and_listing_revisions.sql

docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
DO $$
DECLARE object_count integer; feed_sequence bigint; compare_result integer;
BEGIN
  SELECT count(*) INTO object_count FROM information_schema.tables
  WHERE table_schema='public' AND table_name IN (
    'marketplace_plugins','marketplace_releases','marketplace_media_assets','marketplace_audit',
    'publication_promotions','release_signatures','marketplace_advisory_feed_state',
    'marketplace_release_advisory_events','marketplace_dev_import_jobs',
    'marketplace_dev_import_archives','marketplace_dev_import_artifacts','marketplace_listing_revisions');
  IF object_count <> 12 THEN RAISE EXCEPTION 'V1-V2 schema is incomplete: found % tables', object_count; END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public'
      AND table_name='marketplace_plugins' AND column_name='listing_state') THEN
    RAISE EXCEPTION 'obsolete plugin listing state remains in the current schema';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public'
      AND table_name='marketplace_plugins' AND column_name='moderation_state') THEN
    RAISE EXCEPTION 'current plugin moderation state is missing';
  END IF;
  SELECT sequence INTO feed_sequence FROM marketplace_advisory_feed_state WHERE singleton_id=true;
  IF feed_sequence <> 1 THEN RAISE EXCEPTION 'advisory feed sequence was not initialized'; END IF;
  compare_result := marketplace_semver_compare('1.10.0','1.9.9');
  IF compare_result <= 0 THEN RAISE EXCEPTION 'SemVer comparison function failed'; END IF;
  IF marketplace_semver_sort_key('1.10.0') COLLATE "C" <= marketplace_semver_sort_key('1.9.9') COLLATE "C" THEN
    RAISE EXCEPTION 'SemVer sort key failed';
  END IF;
  IF (SELECT count(*) FROM marketplace_releases WHERE plugin_id='migration.fixture' AND version='1.0.0') <> 1 THEN
    RAISE EXCEPTION 'forward migration did not preserve the existing published release';
  END IF;
END $$;
SQL

echo 'V1-to-V2 forward migration test passed: developer administration tables were added and existing Marketplace releases were preserved.'
