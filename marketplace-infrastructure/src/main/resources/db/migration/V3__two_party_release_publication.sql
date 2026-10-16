-- A publisher may stage content, but publishing requires approval from the
-- separate operator credential. Bind approval to both immutable runtime
-- manifest and complete catalog metadata so any edit invalidates approval.
ALTER TABLE marketplace_releases
  ADD COLUMN publication_approval_state varchar(20) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN publication_approved_by varchar(120),
  ADD COLUMN publication_approved_at timestamptz,
  ADD COLUMN approved_manifest_sha256 char(64),
  ADD COLUMN approved_catalog_sha256 char(64),
  ADD CONSTRAINT marketplace_release_approval_state_check
    CHECK (publication_approval_state IN ('PENDING','APPROVED')),
  ADD CONSTRAINT marketplace_release_approval_shape_check CHECK (
    (publication_approval_state='PENDING'
      AND publication_approved_by IS NULL
      AND publication_approved_at IS NULL
      AND approved_manifest_sha256 IS NULL
      AND approved_catalog_sha256 IS NULL)
    OR (publication_approval_state='APPROVED'
      AND publication_approved_by IS NOT NULL
      AND publication_approved_at IS NOT NULL
      AND approved_manifest_sha256 ~ '^[0-9a-f]{64}$'
      AND approved_catalog_sha256 ~ '^[0-9a-f]{64}$')
  );

CREATE INDEX marketplace_releases_pending_approval_idx
  ON marketplace_releases(created_at)
  WHERE publication_state='VALIDATED' AND publication_approval_state='PENDING';
