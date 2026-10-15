# Security model

## Trust boundaries

- Anonymous users can read published public catalog entries and media. Publisher operations require a bearer token whose SHA-256 digest is configured as a secret. The raw token is not stored in PostgreSQL.
- Zot requires the publisher account for writes. Its anonymous ACL grants read only to `public/**`; other repositories require publisher credentials. Keep the htpasswd secret outside source control and mount it read-only.
- Plugin manifests are parsed against the pinned Core v1 Java contract and checked against one operator-configured registry host. Every main/sidecar OCI reference must be digest-pinned and available from Zot.
- Catalog text rejects markup and unsafe schemes; external links require HTTPS. Uploaded media is format-sniffed, decoded, bounded by size/pixels, content-addressed, and hash-checked on read.
- PostgreSQL stores public listing data, release state, media metadata, and an append-style audit log. Published release content is immutable at database layer.
- Marketplace API requests have bounded in-process fixed-window limits (120 public and 30 publisher requests per client per minute by default), bounded client-bucket memory, and a standard `429` response with `Retry-After`. Forwarded client addresses are honored only when the immediate peer matches `MARKETPLACE_RATE_LIMIT_TRUSTED_PROXIES`; never trust arbitrary client-supplied forwarding headers.
- Tomcat bounds request headers, form bodies, connection wait, keep-alive time and request read size. The external HTTPS proxy must additionally enforce request/body deadlines and the configured media request-size boundary.

## Deployment requirements

Use TLS at a managed reverse proxy for non-local access, restrict network paths, protect secrets in an orchestrator secret store, patch the host/runtime, review pinned image digests, apply rate and body-size limits, and monitor authentication failures and disk usage. Local Compose ports are loopback-only and its HTTP registry is for development. Never publish publisher bearer credentials in arguments, CI logs, scripts, or plugin packages.

The v1 bearer token is a single shared publisher credential, not an identity system. It provides no per-publisher permissions or self-service account management. Limit it to trusted release operators and rotate it promptly if exposed. Public catalog visibility is only granted through publish. Do not grant anonymous Zot access to private repositories.

## Known scope limits

There is no backoffice UI, payment, licensing, or multi-publisher authorization in v1. Core does not validate installed dependency state at marketplace publication time; Marketplace checks the copied manifest contract and image existence but cannot assert that a future Core instance satisfies installation-time policy. Core's pinned source maps versioned `/api/v1/admin/extensions/**` requests through its API versioning filter; see [compatibility notes](CORE_COMPATIBILITY.md).

## Temporary upstream vulnerability exceptions (review by 2026-10-30)

The 2026-10-15 Trivy scan found patched Jackson 2/3 and Tomcat dependencies in the Boot 4.1.1 BOM. Marketplace pins safe patched versions (Jackson 2.21.7, Jackson annotations 2.22, Jackson 3.2.3 and Tomcat 11.0.25) using Gradle resolution rules; the Boot plugin and approved Spring Boot line remain unchanged. Jackson annotations 2.22 supplies `@JsonApplyView`, which Jackson 3.2 requires.

The same scan found three CRITICAL Tomcat findings and five HIGH Go findings in Zot. CVE-2026-65182 has a Tomcat fix and is not excepted. CVE-2026-65905 and CVE-2026-68525 had no fixed Tomcat version in the scan database. Zot is pinned to the latest upstream v2.1.21 image digest (`sha256:6b69512c00dceaad05b1144e6079aac6aa7309d7fd200f9947ecb1de09cf48c8`); that release contains Go 1.26.8, `golang.org/x/net` v0.58.0 and gRPC v1.83.1, while fixes require newer components and no later upstream Zot release was available during review. Exact CVE IDs have an expiring `.trivyignore` entry through 2026-10-30; these are accepted, time-bounded upstream risks, not false-positive or non-exploitability claims. Keep Zot behind TLS, enforce its anonymous-read/publisher-write ACL, restrict publisher ingress, and reevaluate the upstream release before the exception expires. The CI scan still fails any other HIGH/CRITICAL finding.
