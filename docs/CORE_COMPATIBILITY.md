# Artio Core compatibility record

## Source pin

- Repository: `https://github.com/dvoracek-martin/artio`
- Branch at implementation start: `main`
- Commit: `911f8196b1d76113804e274009e07ada17f15b33`
- Commit subject/date: `fix(ci): account for upstream observability CVEs` / 2026-10-15
- Worktree status at inspection: 43 modified/deleted/untracked paths. No worktree content was used as authoritative source; only `git show HEAD:<path>` was read. Core remains untouched by Marketplace work.
- Core Spring Boot: 4.1.1 (root Gradle plugin declaration); Gradle wrapper: 9.8.1.

## Runtime contract observed at the pinned commit

`marketplace-contract` contains an unchanged source snapshot from the pinned Core commit of:

- `artio-extensions/.../PluginManifest.java`
- `artio-extensions/.../PluginManifestValidator.java`
- `artio-extensions/.../PluginDependencyPolicy.java`

The record field names and nested record types are the Core v1 contract. `PluginManifestValidator` also owns limits and allowlists for IDs, SHA-pinned image references, Core SemVer bounds, permissions, extension points, dependencies, config fields, paths, geographic availability and up to eight sidecars. It takes the actual Core version, the trusted registry list, and installed plugin IDs; publisher validation cannot claim those customer-specific install checks have been completed. Marketplace passes the manifest's minimum supported Core version as a syntax/range fixture and declared dependencies as a structural fixture only. Core must still validate the real customer version, installed dependencies, configuration and lifecycle at install time.

Core Docker runtime starts the primary image and sidecars in declared dependency order, checks each health path, persists declared volumes and enforces image digests and resource boundaries. `ExtensionRuntimeController` uses an internal controller protocol. Marketplace does not start or invoke plugin containers.

## Versioned Core management routes

At the pinned commit, `ExtensionManagementController` has an internal Spring mapping `@RequestMapping("/api/admin/extensions")`. Core's `ApiVersioningFilter` maps canonical incoming `/api/v1/...` requests to those existing handlers, and Core's integration tests exercise the management routes under `/api/v1/admin/extensions/**`. Use the versioned public paths documented by Core: `/validate`, `/register`, `/{pluginId}/configuration`, `/install`, `/enable`, `/disable`, `/update`, `/rollback`, `/{pluginId}/health`, `/{pluginId}/invoke`, `/{pluginId}/audit`, and deletion/uninstall. The internal extension-controller routes under `/internal/v1/extensions/**` are a separate private runtime protocol. Marketplace adds no Core client and does not call the legacy aliases.

## Template review

`stripe-payments`, `swiss-post`, `opensearch-search`, and `observability` templates use the same `manifestVersion: "1"` structure. Their image fields contain `REPLACE_WITH_PUBLISHED_IMAGE_DIGEST`; these are build/publish templates and not valid distributable release references. OpenSearch has one sidecar; Observability declares four and includes startup dependencies and persistent paths. The Marketplace rejects placeholders and verifies all image descriptors in Zot.

There is a concrete validator conflict in the pinned Core sources: `opensearch-search.template.json` declares `/usr/share/opensearch/data` as a persistent sidecar path, while `PluginManifestValidator` accepts only `/data/...`, `/var/lib/...`, or `/prometheus`. Marketplace intentionally preserves the pinned validator and rejects that unmodified template; the other three Core templates pass the Marketplace contract test after substituting syntactically valid digest references. This is not silently normalized because Core would apply the same validator during installation. Before publishing an OpenSearch release, Core must change the template path to an allowed path (and confirm the image supports it) or the Core validator must deliberately widen its allowlist, followed by a new Core pin and compatibility review.

## Executable reference fixture

`examples/reference-plugin` is a small independent implementation of the observed Core v1 HTTP protocol. The Docker acceptance builds releases `1.2.0` and `1.3.0`, pushes and publishes digest-pinned main and companion OCI artifacts, then anonymously pulls both services. It runs the v1.2.0 image in a restricted container and checks health, diagnostics, missing-token rejection, authenticated invocation, and deterministic idempotent retry. When `ARTIO_CORE_PATH` points to the pinned source checkout, acceptance passes Marketplace-published digests to isolated Core runtime/controller integration tests; it does not connect to a deployed customer Core. In GitHub Actions run `38822610510` at Marketplace commit `57da9bbc295fd919809ecdb279399e39aef8f644`, the two Docker runtime lifecycle tests and the catalog dependency/version test passed, one egress test was skipped by the Core fixture, and `PackagedExtensionControllerIntegrationTests.packagedControllerAuthenticatesAndManagesPrivateBundleWithoutCommerceServices` failed at Core test line 146 with a `NullPointerException` while dereferencing bundle-health. Artio Core was not edited. This is recorded as a pinned-Core compatibility blocker, not a Marketplace pass.

The full admin API install/configure/enable flow with the exact retrieved Marketplace manifest is not yet tested. It needs a separate isolated Core harness with its setup/security and Docker runtime configuration; no deployed or customer Core is assumed. A Marketplace outage smoke can independently verify that an already-running reference plugin still serves health/invocation, but it cannot substitute for the Core-side offline lifecycle assertion. Core must continue to run its normal version, registry-trust, dependency, configuration and lifecycle checks for every customer installation.

## Drift gate

CI checks out the Core repository at the exact pin and byte-compares the three copied sources to this module. It builds isolated runtime/controller fixtures from that checkout; there is no standing Core process or Marketplace-to-Core client connection. Updating the Core contract requires an explicit pin change, copied-source review, fixture update and contract test update. Marketplace does not depend on the whole Core build or Spring application.
