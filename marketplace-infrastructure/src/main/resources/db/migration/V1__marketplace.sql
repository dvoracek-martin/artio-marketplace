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

CREATE TABLE marketplace_plugins (
  plugin_id varchar(160) PRIMARY KEY,
  slug varchar(120) NOT NULL UNIQUE,
  vendor varchar(120) NOT NULL,
  listing_state varchar(20) NOT NULL CHECK (listing_state IN ('LISTED','UNLISTED','REVOKED')),
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
  publication_state varchar(20) NOT NULL CHECK (publication_state IN ('DRAFT','VALIDATED','PUBLISHED','UNLISTED','REVOKED')),
  idempotency_key varchar(160),
  create_request_sha256 char(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  UNIQUE (plugin_id, version),
  UNIQUE (idempotency_key)
);
CREATE INDEX marketplace_releases_public_idx ON marketplace_releases(plugin_id, published_at DESC) WHERE publication_state='PUBLISHED';
CREATE INDEX marketplace_plugin_slug_idx ON marketplace_plugins USING gin(slug gin_trgm_ops);

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
