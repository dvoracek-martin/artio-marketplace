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

CREATE FUNCTION marketplace_reject_release_signature_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'signed release descriptors are immutable';
END $$;
CREATE TRIGGER release_signature_immutable BEFORE UPDATE OR DELETE ON release_signatures
FOR EACH ROW EXECUTE FUNCTION marketplace_reject_release_signature_mutation();
