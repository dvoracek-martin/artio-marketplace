CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE OR REPLACE FUNCTION marketplace_semver_compare(left_version text, right_version text)
RETURNS integer LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
  left_parts text[]; right_parts text[];
  left_pre text[]; right_pre text[];
  i integer; left_id text; right_id text;
BEGIN
  left_parts := regexp_match(left_version, '^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)(-([0-9A-Za-z.-]+))?([+][0-9A-Za-z.-]+)?$');
  right_parts := regexp_match(right_version, '^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)(-([0-9A-Za-z.-]+))?([+][0-9A-Za-z.-]+)?$');
  IF left_parts IS NULL OR right_parts IS NULL THEN RAISE EXCEPTION 'invalid semantic version'; END IF;
  FOR i IN 1..3 LOOP
    IF left_parts[i]::numeric < right_parts[i]::numeric THEN RETURN -1; END IF;
    IF left_parts[i]::numeric > right_parts[i]::numeric THEN RETURN 1; END IF;
  END LOOP;
  IF left_parts[5] IS NULL AND right_parts[5] IS NOT NULL THEN RETURN 1; END IF;
  IF left_parts[5] IS NOT NULL AND right_parts[5] IS NULL THEN RETURN -1; END IF;
  IF left_parts[5] IS NULL THEN RETURN 0; END IF;
  left_pre := string_to_array(left_parts[5], '.');
  right_pre := string_to_array(right_parts[5], '.');
  FOR i IN 1..greatest(array_length(left_pre, 1), array_length(right_pre, 1)) LOOP
    IF i > array_length(left_pre, 1) THEN RETURN -1; END IF;
    IF i > array_length(right_pre, 1) THEN RETURN 1; END IF;
    left_id := left_pre[i]; right_id := right_pre[i];
    IF left_id = right_id THEN CONTINUE; END IF;
    IF left_id ~ '^[0-9]+$' AND left_id ~ '^0[0-9]+$' OR right_id ~ '^[0-9]+$' AND right_id ~ '^0[0-9]+$' THEN RAISE EXCEPTION 'invalid numeric prerelease identifier'; END IF;
    IF left_id ~ '^[0-9]+$' AND right_id ~ '^[0-9]+$' THEN
      IF left_id::numeric < right_id::numeric THEN RETURN -1; ELSE RETURN 1; END IF;
    ELSIF left_id ~ '^[0-9]+$' THEN RETURN -1;
    ELSIF right_id ~ '^[0-9]+$' THEN RETURN 1;
    ELSIF left_id COLLATE "C" < right_id COLLATE "C" THEN RETURN -1;
    ELSE RETURN 1;
    END IF;
  END LOOP;
  RETURN 0;
END $$;

-- Current development schema. Keep this complete snapshot in V1 until the
-- first production release establishes a supported database upgrade path.
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

CREATE TABLE marketplace_plugins (
  plugin_id varchar(160) PRIMARY KEY,
  slug varchar(120) NOT NULL UNIQUE,
  vendor varchar(120) NOT NULL,
  moderation_state varchar(24) NOT NULL DEFAULT 'ACTIVE'
    CHECK (moderation_state IN ('ACTIVE','SUSPENDED','BANNED','REVIEW_REQUIRED')),
  moderation_review_reason text,
  moderation_updated_at timestamptz NOT NULL DEFAULT now(),
  moderation_version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE marketplace_releases (
  release_id uuid PRIMARY KEY,
  plugin_id varchar(160) NOT NULL REFERENCES marketplace_plugins(plugin_id),
  version varchar(80) NOT NULL,
  manifest jsonb NOT NULL,
  manifest_document text NOT NULL,
  manifest_sha256 char(64) NOT NULL,
  metadata jsonb NOT NULL,
  publication_state varchar(20) NOT NULL CHECK (publication_state IN ('DRAFT','VALIDATED','PUBLISHING','PUBLISHED','UNLISTED','REVOKED')),
  idempotency_key varchar(160),
  create_request_sha256 char(64) NOT NULL,
  publishing_started_at timestamptz,
  row_version bigint NOT NULL DEFAULT 0,
  revoked_at timestamptz,
  revocation_category varchar(40),
  revocation_reason varchar(1000),
  revoked_by varchar(120),
  publication_approval_state varchar(20) NOT NULL DEFAULT 'PENDING'
    CHECK (publication_approval_state IN ('PENDING','APPROVED')),
  publication_approved_by varchar(120),
  publication_approved_at timestamptz,
  approved_manifest_sha256 char(64),
  approved_catalog_sha256 char(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  UNIQUE (plugin_id, version),
  UNIQUE (idempotency_key),
  CONSTRAINT marketplace_release_revocation_shape_check CHECK (
    (publication_state='REVOKED' AND revoked_at IS NOT NULL AND revocation_category IS NOT NULL AND revoked_by IS NOT NULL)
    OR (publication_state<>'REVOKED' AND revoked_at IS NULL AND revocation_category IS NULL AND revocation_reason IS NULL AND revoked_by IS NULL)
  ),
  CONSTRAINT marketplace_release_approval_shape_check CHECK (
    (publication_approval_state='PENDING' AND publication_approved_by IS NULL AND publication_approved_at IS NULL
      AND approved_manifest_sha256 IS NULL AND approved_catalog_sha256 IS NULL)
    OR (publication_approval_state='APPROVED' AND publication_approved_by IS NOT NULL AND publication_approved_at IS NOT NULL
      AND approved_manifest_sha256 ~ '^[0-9a-f]{64}$' AND approved_catalog_sha256 ~ '^[0-9a-f]{64}$')
  )
);
CREATE INDEX marketplace_releases_public_idx ON marketplace_releases(plugin_id, published_at DESC) WHERE publication_state='PUBLISHED';
CREATE INDEX marketplace_releases_moderation_idx ON marketplace_releases(plugin_id, publication_state, version);
CREATE INDEX marketplace_releases_publishing_lease_idx ON marketplace_releases(publishing_started_at) WHERE publication_state='PUBLISHING';
CREATE INDEX marketplace_releases_semver_idx
  ON marketplace_releases(plugin_id, marketplace_semver_sort_key(version) DESC, version COLLATE "C" DESC)
  WHERE publication_state='PUBLISHED';
CREATE INDEX marketplace_releases_pending_approval_idx ON marketplace_releases(created_at)
  WHERE publication_state='VALIDATED' AND publication_approval_state='PENDING';
CREATE INDEX marketplace_plugin_slug_idx ON marketplace_plugins USING gin(slug gin_trgm_ops);

CREATE TABLE publication_promotions (
  release_id uuid PRIMARY KEY REFERENCES marketplace_releases(release_id) ON DELETE RESTRICT,
  state varchar(20) NOT NULL CHECK (state IN ('PROMOTING','RETRYABLE','PUBLISHED')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  started_at timestamptz,
  completed_at timestamptz,
  last_error_code varchar(80),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT publication_promotions_completed_shape_check CHECK (
    (state='PUBLISHED' AND completed_at IS NOT NULL AND last_error_code IS NULL)
    OR (state<>'PUBLISHED' AND completed_at IS NULL)
  )
);
CREATE INDEX publication_promotions_retry_idx ON publication_promotions(updated_at) WHERE state='RETRYABLE';

CREATE TABLE release_signatures (
  release_id uuid NOT NULL REFERENCES marketplace_releases(release_id),
  key_id varchar(120) NOT NULL,
  descriptor_sha256 char(64) NOT NULL CHECK (descriptor_sha256 ~ '^[0-9a-f]{64}$'),
  descriptor_document text NOT NULL,
  signature_base64url varchar(256) NOT NULL,
  signed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (release_id, key_id)
);
CREATE INDEX release_signatures_signed_at_idx ON release_signatures(release_id, signed_at DESC);

CREATE TABLE marketplace_advisory_feed_state (
  singleton_id boolean PRIMARY KEY DEFAULT true CHECK (singleton_id),
  sequence bigint NOT NULL CHECK (sequence > 0),
  issued_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO marketplace_advisory_feed_state(singleton_id,sequence,issued_at) VALUES (true,1,now());

CREATE TABLE marketplace_release_advisory_events (
  sequence bigint PRIMARY KEY,
  advisory_id uuid NOT NULL UNIQUE,
  release_id uuid NOT NULL REFERENCES marketplace_releases(release_id),
  plugin_id varchar(160) NOT NULL,
  version varchar(80) NOT NULL,
  release_descriptor_digest char(64) NOT NULL CHECK (release_descriptor_digest ~ '^[0-9a-f]{64}$'),
  image_digests jsonb NOT NULL CHECK (jsonb_typeof(image_digests)='array'),
  support_status varchar(16) NOT NULL CHECK (support_status IN ('SUPPORTED','UNSUPPORTED','REMOVED')),
  security_status varchar(16) NOT NULL CHECK (security_status IN ('ALLOWED','BANNED')),
  banned_image_digests jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(banned_image_digests)='array'),
  severity varchar(16) NOT NULL CHECK (severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
  reason_code varchar(48) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
  explanations jsonb NOT NULL CHECK (jsonb_typeof(explanations)='object'),
  actor varchar(120) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (security_status<>'BANNED' OR jsonb_array_length(banned_image_digests)>0)
);
CREATE INDEX marketplace_release_advisory_latest_idx ON marketplace_release_advisory_events(release_id,sequence DESC);

CREATE TABLE marketplace_media_assets (
  asset_id varchar(80) PRIMARY KEY,
  sha256 char(64) NOT NULL UNIQUE,
  relative_path varchar(180) NOT NULL UNIQUE,
  content_type varchar(40) NOT NULL CHECK (content_type IN ('image/png','image/jpeg','image/webp')),
  byte_size bigint NOT NULL CHECK(byte_size > 0 AND byte_size <= 5242880),
  width integer NOT NULL CHECK(width > 0),
  height integer NOT NULL CHECK(height > 0),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE marketplace_audit (
  audit_id bigserial PRIMARY KEY,
  actor varchar(120) NOT NULL,
  action varchar(40) NOT NULL,
  target varchar(200) NOT NULL,
  outcome varchar(20) NOT NULL,
  correlation_id varchar(100),
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION marketplace_reject_release_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.publication_state IN ('PUBLISHED','UNLISTED','REVOKED') AND
     (NEW.manifest <> OLD.manifest OR NEW.manifest_sha256 <> OLD.manifest_sha256 OR NEW.plugin_id <> OLD.plugin_id
       OR NEW.version <> OLD.version OR NEW.metadata <> OLD.metadata OR NEW.create_request_sha256 <> OLD.create_request_sha256) THEN
    RAISE EXCEPTION 'published release identity is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER marketplace_release_immutable BEFORE UPDATE ON marketplace_releases
FOR EACH ROW EXECUTE FUNCTION marketplace_reject_release_mutation();

CREATE FUNCTION marketplace_reject_release_signature_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'signed release descriptors are immutable';
END $$;
CREATE TRIGGER release_signature_immutable BEFORE UPDATE OR DELETE ON release_signatures
FOR EACH ROW EXECUTE FUNCTION marketplace_reject_release_signature_mutation();

CREATE FUNCTION marketplace_reject_advisory_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'signed advisory history is immutable';
END $$;
CREATE TRIGGER marketplace_release_advisory_immutable BEFORE UPDATE OR DELETE ON marketplace_release_advisory_events
FOR EACH ROW EXECUTE FUNCTION marketplace_reject_advisory_mutation();
