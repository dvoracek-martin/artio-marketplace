-- Forward-only additions for the local developer administration workflow.
-- Existing release rows, signed descriptors, advisory events, and Zot blobs are untouched.

CREATE TABLE marketplace_dev_import_jobs (
  job_id uuid PRIMARY KEY,
  source varchar(32) NOT NULL CHECK (source = 'LOCAL_DEV_UI'),
  state varchar(24) NOT NULL CHECK (state IN (
    'CREATED','RECEIVING','UPLOADED','INSPECTING','INSPECTED','IMPORTING',
    'STAGED','RELEASE_READY','FAILED','CANCELLED','EXPIRED'
  )),
  plugin_id varchar(160),
  plugin_version varchar(80),
  requested_role varchar(72) NOT NULL DEFAULT 'MAIN' CHECK (requested_role = 'MAIN' OR requested_role ~ '^SIDECAR:[a-z0-9][a-z0-9._-]{0,63}$'),
  source_image_tag varchar(255),
  archive_filename varchar(255),
  archive_sha256 char(64) CHECK (archive_sha256 IS NULL OR archive_sha256 ~ '^[a-f0-9]{64}$'),
  idempotency_key varchar(160) NOT NULL UNIQUE,
  received_bytes bigint NOT NULL DEFAULT 0 CHECK (received_bytes >= 0),
  error_code varchar(80),
  retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
  CHECK ((plugin_id IS NULL AND plugin_version IS NULL)
      OR (plugin_id ~ '^[a-z0-9][a-z0-9._-]{0,127}$'
          AND plugin_version ~ '^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?([+][0-9A-Za-z.-]+)?$'))
);
CREATE INDEX marketplace_dev_import_jobs_active_idx
  ON marketplace_dev_import_jobs(updated_at) WHERE state IN ('CREATED','RECEIVING','UPLOADED','INSPECTING','INSPECTED','IMPORTING');
CREATE INDEX marketplace_dev_import_jobs_expiry_idx
  ON marketplace_dev_import_jobs(expires_at) WHERE state IN ('FAILED','CANCELLED','EXPIRED');

CREATE TABLE marketplace_dev_import_archives (
  archive_id uuid PRIMARY KEY,
  job_id uuid NOT NULL REFERENCES marketplace_dev_import_jobs(job_id) ON DELETE CASCADE,
  original_filename varchar(255) NOT NULL,
  archive_format varchar(20) NOT NULL CHECK (archive_format IN ('DOCKER_SAVE','OCI_LAYOUT')),
  sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  byte_size bigint NOT NULL CHECK (byte_size > 0 AND byte_size <= 4294967296),
  inventory jsonb NOT NULL CHECK (jsonb_typeof(inventory) = 'array'),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(job_id, archive_id),
  UNIQUE(job_id, sha256, original_filename)
);
CREATE INDEX marketplace_dev_import_archives_job_idx
  ON marketplace_dev_import_archives(job_id, created_at, archive_id);

CREATE TABLE marketplace_dev_import_artifacts (
  artifact_id uuid PRIMARY KEY,
  job_id uuid NOT NULL REFERENCES marketplace_dev_import_jobs(job_id) ON DELETE CASCADE,
  archive_id uuid NOT NULL,
  role varchar(72) NOT NULL CHECK (role = 'MAIN' OR role ~ '^SIDECAR:[a-z0-9][a-z0-9._-]{0,63}$'),
  source_image varchar(255) NOT NULL,
  staging_repository varchar(255) NOT NULL CHECK (staging_repository ~ '^staging/plugins/[a-z0-9][a-z0-9._/-]*$'),
  staging_tag varchar(80) NOT NULL,
  digest varchar(71) NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
  media_type varchar(160) NOT NULL,
  platforms jsonb NOT NULL CHECK (jsonb_typeof(platforms) = 'array'),
  public_reference varchar(600) NOT NULL CHECK (public_reference ~ '^[A-Za-z0-9.-]+(:[0-9]{1,5})?/public/plugins/.+@sha256:[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(job_id, role),
  UNIQUE(staging_repository, staging_tag),
  FOREIGN KEY(job_id, archive_id) REFERENCES marketplace_dev_import_archives(job_id, archive_id) ON DELETE CASCADE
);
CREATE INDEX marketplace_dev_import_artifacts_job_idx
  ON marketplace_dev_import_artifacts(job_id, role);

CREATE TABLE marketplace_listing_revisions (
  revision_id uuid PRIMARY KEY,
  plugin_id varchar(160) NOT NULL REFERENCES marketplace_plugins(plugin_id) ON DELETE RESTRICT,
  revision_number bigint NOT NULL CHECK (revision_number > 0),
  metadata jsonb NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
  approval_state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (approval_state IN ('PENDING','APPROVED','REJECTED')),
  created_by varchar(120) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expected_listing_version bigint NOT NULL CHECK (expected_listing_version >= 0),
  approved_by varchar(120),
  approved_at timestamptz,
  approval_reason varchar(1000),
  row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0),
  rollback_source_revision_id uuid,
  UNIQUE(plugin_id, revision_id),
  UNIQUE(plugin_id, revision_number),
  FOREIGN KEY(plugin_id, rollback_source_revision_id)
    REFERENCES marketplace_listing_revisions(plugin_id, revision_id) ON DELETE RESTRICT,
  CONSTRAINT marketplace_listing_revision_approval_shape CHECK (
    (approval_state = 'PENDING' AND approved_by IS NULL AND approved_at IS NULL AND approval_reason IS NULL)
    OR (approval_state = 'APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND approval_reason IS NOT NULL)
    OR (approval_state = 'REJECTED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND approval_reason IS NOT NULL)
  )
);
CREATE INDEX marketplace_listing_revisions_history_idx
  ON marketplace_listing_revisions(plugin_id, revision_number DESC);
CREATE INDEX marketplace_listing_revisions_pending_idx
  ON marketplace_listing_revisions(created_at) WHERE approval_state = 'PENDING';

ALTER TABLE marketplace_plugins
  ADD COLUMN listing_revision_version bigint NOT NULL DEFAULT 0 CHECK (listing_revision_version >= 0),
  ADD COLUMN effective_listing_revision_id uuid;
ALTER TABLE marketplace_plugins
  ADD CONSTRAINT marketplace_plugins_effective_listing_revision_fk
  FOREIGN KEY(plugin_id, effective_listing_revision_id)
  REFERENCES marketplace_listing_revisions(plugin_id, revision_id)
  ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

-- Listing revisions are immutable once created; activating a revision only moves the plugin pointer.
CREATE FUNCTION marketplace_reject_listing_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.revision_id <> OLD.revision_id OR NEW.plugin_id <> OLD.plugin_id
     OR NEW.revision_number <> OLD.revision_number OR NEW.metadata <> OLD.metadata
     OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at
     OR NEW.rollback_source_revision_id IS DISTINCT FROM OLD.rollback_source_revision_id
     OR OLD.approval_state <> 'PENDING' THEN
    RAISE EXCEPTION 'listing revision content is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER marketplace_listing_revision_immutable
  BEFORE UPDATE ON marketplace_listing_revisions
  FOR EACH ROW EXECUTE FUNCTION marketplace_reject_listing_revision_mutation();
CREATE FUNCTION marketplace_reject_listing_revision_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'listing revision history is immutable';
END $$;
CREATE TRIGGER marketplace_listing_revision_no_delete
  BEFORE DELETE ON marketplace_listing_revisions
  FOR EACH ROW EXECUTE FUNCTION marketplace_reject_listing_revision_delete();
