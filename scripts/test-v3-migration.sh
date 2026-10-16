#!/usr/bin/env bash
set -euo pipefail

container="marketplace-v3-migration-${GITHUB_RUN_ID:-local}-$$"
image='postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea'
cleanup() { docker rm --force "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run --detach --rm --name "$container" --tmpfs /var/lib/postgresql/data:rw,size=256m \
  --env POSTGRES_PASSWORD=migration-test-only "$image" >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -U postgres >/dev/null
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres < marketplace-infrastructure/src/main/resources/db/migration/V1__marketplace.sql
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
INSERT INTO marketplace_plugins(plugin_id,slug,vendor,listing_state)
VALUES ('legacy.plugin','legacy-plugin','Legacy Vendor','REVOKED');
INSERT INTO marketplace_releases(
  release_id,plugin_id,version,manifest,manifest_document,manifest_sha256,metadata,
  publication_state,create_request_sha256)
VALUES (
  '00000000-0000-0000-0000-000000000001','legacy.plugin','1.0.0',
  '{"id":"legacy.plugin"}','{"id":"legacy.plugin"}',repeat('a',64),'{"categories":[]}',
  'REVOKED',repeat('b',64));
SQL
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres < marketplace-infrastructure/src/main/resources/db/migration/V2__release_moderation_and_semver.sql
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres < marketplace-infrastructure/src/main/resources/db/migration/V3__two_party_release_publication.sql
result=$(docker exec "$container" psql -At -U postgres -d postgres -c \
  "SELECT p.moderation_state || ':' || r.publication_state || ':' || r.revocation_category || ':' || r.revoked_by FROM marketplace_plugins p JOIN marketplace_releases r USING(plugin_id) WHERE p.plugin_id='legacy.plugin'")
[[ $result == 'REVIEW_REQUIRED:REVOKED:LEGACY_UNKNOWN:legacy-migration' ]] || {
  echo "Legacy V1 data migrated incorrectly: $result" >&2
  exit 1
}
ordering=$(docker exec "$container" psql -At -U postgres -d postgres -c \
  "SELECT marketplace_semver_sort_key('1.10.0') COLLATE \"C\" > marketplace_semver_sort_key('1.9.9') COLLATE \"C\" AND marketplace_semver_sort_key('1.0.0') COLLATE \"C\" > marketplace_semver_sort_key('1.0.0-rc.1') COLLATE \"C\"")
[[ $ordering == t ]] || { echo 'Database SemVer precedence key failed.' >&2; exit 1; }
docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
INSERT INTO marketplace_plugins(plugin_id,slug,vendor)
VALUES ('approval.plugin','approval-plugin','Approval Vendor');
INSERT INTO marketplace_releases(
  release_id,plugin_id,version,manifest,manifest_document,manifest_sha256,metadata,
  publication_state,create_request_sha256)
VALUES (
  '00000000-0000-0000-0000-000000000002','approval.plugin','1.0.0',
  '{"id":"approval.plugin"}','{"id":"approval.plugin"}',repeat('c',64),'{"categories":[]}',
  'VALIDATED',repeat('d',64));
DO $$
DECLARE approval_state text;
BEGIN
  SELECT publication_approval_state INTO approval_state FROM marketplace_releases
  WHERE plugin_id='approval.plugin';
  IF approval_state <> 'PENDING' THEN RAISE EXCEPTION 'new release is not pending approval'; END IF;
END $$;
UPDATE marketplace_releases SET publication_approval_state='APPROVED',
  publication_approved_by='admin',publication_approved_at=now(),
  approved_manifest_sha256=manifest_sha256,approved_catalog_sha256=repeat('e',64)
WHERE plugin_id='approval.plugin';
UPDATE marketplace_releases SET metadata='{"categories":["other"]}'::jsonb,
  publication_approval_state='PENDING',publication_approved_by=NULL,
  publication_approved_at=NULL,approved_manifest_sha256=NULL,approved_catalog_sha256=NULL
WHERE plugin_id='approval.plugin';
DO $$
DECLARE approval_state text; approved_at timestamptz;
BEGIN
  SELECT publication_approval_state,publication_approved_at INTO approval_state,approved_at
  FROM marketplace_releases WHERE plugin_id='approval.plugin';
  IF approval_state <> 'PENDING' OR approved_at IS NOT NULL THEN
    RAISE EXCEPTION 'editing catalog did not reset the approval state';
  END IF;
END $$;
SQL
echo 'V1-V3 migration test passed: ambiguous revocation is quarantined, release history is attributed, SemVer order is numeric, and approval is reset after catalog mutation.'
