# Core backoffice integration contract

This is the intended server-side boundary for a future Artio Core backoffice integration. It is not a claim that the current Core exposes these routes: the pinned Core source currently maps extension administration under the legacy `/api/admin/extensions` route. This Marketplace implements only the documented future versioned contract and does not modify Core.

## Intended Core responsibilities

Core remains responsible for installation, permissions, configuration, dependency state, plugin lifecycle, Docker runtime, and reporting extension status. Marketplace remains responsible for public discovery, publisher release artifacts, listing metadata, OCI image distribution, and publication/revocation state. Core must never rely on a Marketplace compatibility claim as a replacement for its local install-time validation.

## Future endpoint shape

After an explicit Core API migration, a Core administrator may call a versioned endpoint such as `GET /api/v1/admin/extensions/marketplace` to retrieve the configured Marketplace catalog or search. Selecting a release must pass the manifest and immutable release version/digest through the normal Core installation path; Core then runs its own current `PluginManifestValidator`, `PluginDependencyPolicy`, configuration validation, and runtime controller. The endpoint must preserve existing Core authorization and audit requirements.

Before enabling this integration, coordinate an upstream Core API migration, define authentication and pagination, agree on locale and compatibility query behavior, and run end-to-end tests against the released Core version. Do not add legacy unversioned calls to Marketplace.
