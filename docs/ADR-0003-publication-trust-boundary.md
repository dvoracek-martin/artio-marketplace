# ADR-0003: Publication trust boundary

**Status:** Accepted with customer-production prerequisites  
**Date:** 2026-10-16

## Decision

The publisher API token, moderation/admin token, release-revocation token and Zot push credential have separate verifier/secret values and audit actor labels. Publisher authorization is not an identity system. Public users receive read-only catalog and OCI access. OCI references must use the configured public registry hostname, approved repository prefix, and immutable SHA-256 digest; main and sidecar artifacts are checked before publication. The ordinary Zot publisher policy does not grant delete.

The sample edge restricts the admin API by source network and sanitizes forwarded addresses. Application authorization remains mandatory. Operators provision raw secrets outside source control and store only API-token SHA-256 verifiers in application configuration.

## Consequences and limits

Digest and ACL checks do not establish signer identity. Cosign/Sigstore verification and Core-side signature enforcement are **not implemented**; unsigned publisher artifacts remain a customer-production risk. Signing credentials must remain outside Marketplace API containers if signing is added. Core trust policy and installation behavior remain unchanged.

## Verification

Live acceptance denies anonymous writes and registry deletion, checks publisher/admin/revocation role separation, and pulls published image digests. Security scans and current CI evidence are recorded in `docs/ACCEPTANCE.md`.
