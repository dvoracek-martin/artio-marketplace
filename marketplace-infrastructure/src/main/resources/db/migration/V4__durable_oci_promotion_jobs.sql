-- Durable source-of-truth for approval-triggered OCI promotion. OCI bytes remain in Zot.
CREATE TABLE publication_promotions (
  release_id uuid PRIMARY KEY REFERENCES marketplace_releases(release_id) ON DELETE RESTRICT,
  state varchar(20) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  started_at timestamptz,
  completed_at timestamptz,
  last_error_code varchar(80),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT publication_promotions_state_check
    CHECK (state IN ('PROMOTING','RETRYABLE','PUBLISHED')),
  CONSTRAINT publication_promotions_attempt_check CHECK (attempt_count >= 0),
  CONSTRAINT publication_promotions_completed_shape_check CHECK (
    (state='PUBLISHED' AND completed_at IS NOT NULL AND last_error_code IS NULL)
    OR (state<>'PUBLISHED' AND completed_at IS NULL)
  )
);

CREATE INDEX publication_promotions_retry_idx
  ON publication_promotions(updated_at)
  WHERE state='RETRYABLE';
