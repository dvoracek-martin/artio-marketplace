-- Keep the applied V1 schema intact. Legacy REVOKED plugin state was written
-- by the single-release revoke handler and cannot reliably prove a family ban.
ALTER TABLE marketplace_plugins
  ADD COLUMN moderation_state varchar(24) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN moderation_review_reason text,
  ADD COLUMN moderation_updated_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN moderation_version bigint NOT NULL DEFAULT 0,
  ADD CONSTRAINT marketplace_plugin_moderation_state_check
    CHECK (moderation_state IN ('ACTIVE','SUSPENDED','BANNED','REVIEW_REQUIRED'));

UPDATE marketplace_plugins
SET moderation_state='REVIEW_REQUIRED',
    moderation_review_reason='Legacy plugin REVOKED state is ambiguous; review release audit before reactivation',
    moderation_updated_at=now()
WHERE listing_state='REVOKED';

INSERT INTO marketplace_audit(actor,action,target,outcome,details)
SELECT 'legacy-migration','LEGACY_MODERATION_REVIEW',plugin_id,'REVIEW_REQUIRED',
       jsonb_build_object('sourceState','REVOKED','reason','Legacy state did not distinguish plugin ban from release revocation')
FROM marketplace_plugins WHERE moderation_state='REVIEW_REQUIRED';

ALTER TABLE marketplace_plugins DROP CONSTRAINT marketplace_plugins_listing_state_check;
ALTER TABLE marketplace_plugins DROP COLUMN listing_state;

ALTER TABLE marketplace_releases
  DROP CONSTRAINT marketplace_releases_publication_state_check,
  ADD CONSTRAINT marketplace_releases_publication_state_check
    CHECK (publication_state IN ('DRAFT','VALIDATED','PUBLISHING','PUBLISHED','UNLISTED','REVOKED')),
  ADD COLUMN publishing_started_at timestamptz,
  ADD COLUMN row_version bigint NOT NULL DEFAULT 0,
  ADD COLUMN revoked_at timestamptz,
  ADD COLUMN revocation_category varchar(40),
  ADD COLUMN revocation_reason varchar(1000),
  ADD COLUMN revoked_by varchar(120);

UPDATE marketplace_releases
SET revoked_at=coalesce(published_at,created_at),
    revocation_category='LEGACY_UNKNOWN',
    revocation_reason='Legacy revocation; reason was not recorded',
    revoked_by='legacy-migration'
WHERE publication_state='REVOKED';

INSERT INTO marketplace_audit(actor,action,target,outcome,details)
SELECT 'legacy-migration','LEGACY_RELEASE_REVOCATION',plugin_id || '@' || version,'REVOKED',
       jsonb_build_object('category','LEGACY_UNKNOWN','reason','Legacy reason was not recorded')
FROM marketplace_releases WHERE publication_state='REVOKED';

ALTER TABLE marketplace_releases
  ADD CONSTRAINT marketplace_release_revocation_shape_check CHECK (
    (publication_state='REVOKED' AND revoked_at IS NOT NULL AND revocation_category IS NOT NULL AND revoked_by IS NOT NULL)
    OR (publication_state<>'REVOKED' AND revoked_at IS NULL AND revocation_category IS NULL AND revocation_reason IS NULL AND revoked_by IS NULL)
  );

CREATE INDEX marketplace_releases_moderation_idx
  ON marketplace_releases(plugin_id, publication_state, version);
CREATE INDEX marketplace_releases_publishing_lease_idx
  ON marketplace_releases(publishing_started_at)
  WHERE publication_state='PUBLISHING';

-- A lexicographically sortable key implementing SemVer 2.0 precedence. A
-- SemVer value is <=80 characters in V1, so fixed-width numeric components
-- remain bounded. Build metadata is deliberately omitted from precedence.
CREATE OR REPLACE FUNCTION marketplace_semver_sort_key(version text)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
  parts text[];
  prerelease text[];
  identifier text;
  result text;
BEGIN
  parts := regexp_match(version,
    '^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)(-([0-9A-Za-z.-]+))?([+][0-9A-Za-z.-]+)?$');
  IF parts IS NULL THEN RAISE EXCEPTION 'invalid semantic version'; END IF;
  result := lpad(parts[1],80,'0') || '!' || lpad(parts[2],80,'0') || '!' ||
            lpad(parts[3],80,'0') || '!' || CASE WHEN parts[5] IS NULL THEN '1!' ELSE '0!' END;
  IF parts[5] IS NULL THEN RETURN result; END IF;
  prerelease := string_to_array(parts[5], '.');
  FOREACH identifier IN ARRAY prerelease LOOP
    IF identifier = '' THEN RAISE EXCEPTION 'invalid semantic version prerelease'; END IF;
    IF identifier ~ '^[0-9]+$' THEN
      IF identifier ~ '^0[0-9]+$' THEN RAISE EXCEPTION 'invalid numeric prerelease identifier'; END IF;
      result := result || '0' || lpad(identifier,80,'0') || '!';
    ELSE
      result := result || '1' || identifier || '!';
    END IF;
  END LOOP;
  RETURN result;
END $$;

CREATE INDEX marketplace_releases_semver_idx
  ON marketplace_releases(plugin_id, marketplace_semver_sort_key(version) DESC, version COLLATE "C" DESC)
  WHERE publication_state='PUBLISHED';
