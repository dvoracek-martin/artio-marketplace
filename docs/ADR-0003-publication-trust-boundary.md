# ADR-0003: Publication trust boundary

**Status:** Accepted with customer-production prerequisites  
**Date:** 2026-10-16

## Decision

The publisher API token, moderation/admin token, release-revocation token, Zot publisher credential, and Zot promotion credential are distinct. The publisher can read/write only `staging/**`; it has no write access to `public/**`. Anonymous users can read `public/**` only. A separate least-privilege promoter can read staging and write public artifacts; it is used only by Marketplace after an administrator approves the immutable manifest/catalog hashes. The public manifest retains its final `public/plugins/...@sha256:...` references while pre-approval validation resolves those digests under `staging/plugins/**`. After approval, Marketplace copies every OCI index, platform manifest, config and layer blob via the OCI Distribution API, verifies final manifest bytes/digests, and only then finalizes publication. Partial bytes of an approved in-progress bundle may be publicly readable, but no catalog-visible release exists until every artifact is verified. Zot denies anonymous staging reads and all public writes except to the promoter; repositories outside the two named namespaces default deny. No customer Docker daemon or socket is involved.

Approval authorizes the automatic transfer but is not itself a published release. A failed or interrupted transfer stays non-catalog-visible and is safe to retry by immutable digest. Publication remains bound to the exact manifest/catalog hashes and active plugin moderation state.

The sample edge restricts the admin API by source network and sanitizes forwarded addresses. Application authorization remains mandatory. Operators provision raw secrets outside source control and store only API-token SHA-256 verifiers in application configuration.

## Consequences and limits

Digest checks and two-party review do not establish signer identity. Cosign/Sigstore verification and Core-side signed release descriptor enforcement remain **not implemented** and are still required by the security directive. Signed release descriptors must bind final public paths, all image digests, package/release identity, and a trusted publisher key. Core trust policy and installation behavior remain to be implemented.

## Verification

Live acceptance denies anonymous writes and registry deletion, checks publisher/admin/revocation role separation, and pulls published image digests. Security scans and current CI evidence are recorded in `docs/ACCEPTANCE.md`.
