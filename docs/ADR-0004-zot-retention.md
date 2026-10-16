# ADR-0004: Retain OCI artifacts by digest

**Status:** Accepted  
**Date:** 2026-10-16

## Decision

Zot automatic garbage collection is disabled and the ordinary publisher account has no delete permission. PostgreSQL release references do not protect registry blobs from registry GC. Every published manifest's main image and each sidecar must remain pullable by its recorded digest. Unlisting and revocation affect catalog eligibility only; they do not delete OCI data.

Backups include Zot storage, PostgreSQL metadata and catalog media. Restore verification pulls a previously published image by digest. Operators must alert on disk capacity, backup age and failed digest reconciliation. Any future GC/mark-and-sweep process needs a tested retention window, a read-only reconciliation phase and explicit approval before deletion.

## Consequences and limits

Registry storage grows until a separately approved retention process exists. The exact pinned Zot build is tested for anonymous digest pulls, write ACLs, no-delete policy, restart and backup/restore. This is not proof of a future GC implementation; GC remains off.
