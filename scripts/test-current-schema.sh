#!/usr/bin/env bash
set -euo pipefail

container="marketplace-current-schema-${GITHUB_RUN_ID:-local}-$$"
image='postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea'
cleanup() { docker rm --force "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run --detach --rm --name "$container" --tmpfs /var/lib/postgresql/data:rw,size=256m \
  --env POSTGRES_PASSWORD=schema-test-only "$image" >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -U postgres >/dev/null
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
  < marketplace-infrastructure/src/main/resources/db/migration/V1__marketplace.sql

docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
DO $$
DECLARE object_count integer; feed_sequence bigint; compare_result integer;
BEGIN
  SELECT count(*) INTO object_count FROM information_schema.tables
  WHERE table_schema='public' AND table_name IN (
    'marketplace_plugins','marketplace_releases','marketplace_media_assets','marketplace_audit',
    'publication_promotions','release_signatures','marketplace_advisory_feed_state',
    'marketplace_release_advisory_events');
  IF object_count <> 8 THEN RAISE EXCEPTION 'current V1 schema is incomplete: found % tables', object_count; END IF;
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
END $$;
SQL

echo 'Current V1 schema test passed: all Marketplace tables/functions are available from the single development schema snapshot.'
