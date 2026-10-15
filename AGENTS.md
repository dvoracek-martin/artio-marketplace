# Repository guidance

- Artio Core is a read-only compatibility source. Never edit its checkout.
- Preserve the exact Core `PluginManifest v1`; catalog metadata is a separate versioned document.
- Public API paths use `/api/v1/marketplace/**`; publisher paths use `/api/v1/admin/marketplace/**`.
- Keep Java 25, Spring Boot 4.1.1, Gradle 9.8.1 and PostgreSQL 16+.
- Keep domain free of Spring, HTTP, JDBC, registry and file-system dependencies.
- Never put credentials or local absolute paths in Git.
- Verify OCI manifests and every sidecar by immutable digest before publication.
- A published manifest and its artifact references are immutable. Never remove registry blobs used by releases.
