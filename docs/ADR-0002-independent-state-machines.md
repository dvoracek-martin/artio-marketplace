# ADR-0002: Independent plugin moderation and release publication states

**Status:** Accepted  
**Date:** 2026-10-16

## Decision

Plugin moderation (`ACTIVE`, `SUSPENDED`, `BANNED`, `REVIEW_REQUIRED`) is stored separately from each release publication state (`DRAFT`, `VALIDATED`, `PUBLISHING`, `PUBLISHED`, `UNLISTED`, `REVOKED`). Catalog visibility is derived from an active plugin and an eligible published release. “Latest” means the highest SemVer among releases eligible under the request's Core version, country, prerelease preference, publication state and plugin moderation state; publication timestamps do not determine version precedence. Publishing a version never changes moderation state; unlisting or revoking a version never changes sibling releases.

V2 migrates ambiguous legacy plugin `REVOKED` rows to `REVIEW_REQUIRED` and writes a migration audit record. Operators must explicitly review those records. A release revocation is terminal and carries a reason, category, actor and timestamp. CAS/row-version updates guard publication and metadata changes.

## Consequences

New installations and upgrades use the same V2 schema. A suspended or banned plugin cannot finish publication. A revoked release returns `410 Gone` from installable manifest lookup and is excluded from all recommendations; other eligible versions remain available. Existing Core installations are not remotely disabled.

## Verification

The V1→V2 migration script exercises legacy state mapping and SemVer ordering. The Docker acceptance suite verifies release-scoped revocation, sibling latest-compatible selection, plugin suspension, and failed immutable catalog updates. See `docs/POST_IMPLEMENTATION_HARDENING.md` and `docs/ACCEPTANCE.md` for run-specific evidence.
