# Core backoffice integration contract

This is the intended server-side boundary for a future Artio Core backoffice integration. It is not a claim that the current Core exposes these routes: the pinned Core source currently maps extension administration under the legacy `/api/admin/extensions` route. This Marketplace implements only the documented future versioned contract and does not modify Core.

## Intended Core responsibilities

Core remains responsible for installation, permissions, configuration, dependency state, plugin lifecycle, Docker runtime, and reporting extension status. Marketplace remains responsible for public discovery, publisher release artifacts, listing metadata, OCI image distribution, and publication/revocation state. Core must never rely on a Marketplace compatibility claim as a replacement for its local install-time validation.

## Future endpoint shape

The Backoffice can discover plugins through the public Marketplace `/api/v1/marketplace/...` API. Core does not currently expose a Marketplace discovery endpoint. Once an administrator selects a release, the Backoffice submits its exact Core manifest through the existing versioned Core endpoints such as `POST /api/v1/admin/extensions/validate` and `POST /api/v1/admin/extensions/register`, then follows Core's configuration, install, and explicit enable lifecycle. Core runs its own current `PluginManifestValidator`, `PluginDependencyPolicy`, configuration validation, registry trust check, and runtime controller.

Before shipping a Backoffice integration, define its server-side Marketplace authentication policy, pagination and locale behavior, and run end-to-end tests against the released Core version. Do not send publisher credentials to a browser or add legacy unversioned Core calls to Marketplace.
