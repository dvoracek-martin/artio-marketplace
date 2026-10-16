# Core backoffice integration contract

This documents the server-side boundary used by the current Artio Core Backoffice integration. Core exposes versioned proxy routes under `/api/v1/admin/extensions/marketplace/**`; it calls the configured public Marketplace API and never sends publisher or moderation credentials to the browser. The Core release-import flow currently consumes the original v1 runtime manifest and keeps installation/lifecycle responsibilities in Core.

## Intended Core responsibilities

Core remains responsible for installation, permissions, configuration, dependency state, plugin lifecycle, Docker runtime, and reporting extension status. Marketplace remains responsible for public discovery, publisher release artifacts, listing metadata, OCI image distribution, and publication/revocation state. Core must never rely on a Marketplace compatibility claim as a replacement for its local install-time validation.

## Future endpoint shape

The Backoffice discovers compatible plugins through Core's `/api/v1/admin/extensions/marketplace/plugins`, which calls the public Marketplace `/api/v1/marketplace/plugins` endpoint with Core's version and optional country. Selecting a release asks Core for its original manifest through `/api/v1/admin/extensions/marketplace/plugins/{pluginId}/releases/{version}/manifest`; Core then registers it through its own validator and follows the normal configuration, install, and explicit enable lifecycle. Runtime installation pulls the digest-pinned OCI image from the trusted registry. The Marketplace compatibility result is advisory: Core performs local validation for dependencies, geographic availability, registry trust, configuration, and runtime policy.

Marketplace releases remain on the Core `PluginManifest` v1 contract throughout development, as directed for the pre-production product. Core capability/subscription v2 distribution is deferred until the first production release; do not add a second manifest format to Marketplace during the current development version. Preserve existing v1 listings and release artifacts. Never add unversioned Core API calls.
