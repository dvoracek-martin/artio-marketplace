# Architecture

The Gradle project separates `marketplace-domain`, `marketplace-application`, `marketplace-contract`, `marketplace-infrastructure` and `marketplace-server`. Domain value objects are framework-free. Application defines ports. Infrastructure provides PostgreSQL/Flyway, filesystem media storage and a constrained Zot OCI verifier. Spring MVC and the publisher-token check live at the HTTP edge.

`PluginManifest v1` remains Core-owned. A separate `CatalogMetadataV1` JSON document has `schemaVersion: 1`; it is stored beside the unmodified runtime manifest and is never merged into it. Publication workflow states (`DRAFT`, `VALIDATED`, `PUBLISHED`, `UNLISTED`, `REVOKED`) are independent of Artio Core install/enable states.

Release publication is synchronous: validate the pinned Core source contract and catalog metadata, verify all main/sidecar OCI digests through one configured Zot endpoint, check uploaded media references, then commit public status and audit within a PostgreSQL transaction. Registry calls happen before the final transaction; retry is safe via `Idempotency-Key`, immutable release uniqueness and repeated registry verification. The Marketplace has no Docker socket and never starts customer extensions.

The packaged container runs Spring Boot as PID 1 and starts the pinned Zot binary as a supervised child using Spring `SmartLifecycle`. It observes child termination, exits nonzero if Zot unexpectedly exits, forwards graceful shutdown and reaps the child. PostgreSQL runs separately. The reverse proxy terminates TLS and routes Marketplace host traffic to 8080 and registry host traffic to 5000.
