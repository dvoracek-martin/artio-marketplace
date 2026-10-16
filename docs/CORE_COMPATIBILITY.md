# Artio Core compatibility record

## Source pin

- Repository: `https://github.com/dvoracek-martin/artio`
- Branch at implementation start: `main`
- Commit: `46621fa74ac9e88271db83570b1379d80ec945bc`
- This public Core pin includes the v5.1 capability-envelope v2 contract, signed release-descriptor verification, durable registered-plugin installation jobs, and signed Marketplace advisory verification with local exact-digest enforcement. Marketplace CI checks out this commit in an isolated workspace and does not modify Core.
- Core Spring Boot: 4.1.1 (root Gradle plugin declaration); Gradle wrapper: 9.8.1.

## Runtime manifest contracts observed at the pinned commit

`marketplace-contract` retains the Core v1 runtime types and adds the v5.1 v2 envelope types/validators from this Core pin:

- `artio-extensions/.../PluginManifest.java`
- `artio-extensions/.../PluginManifestValidator.java`
- `artio-extensions/.../PluginDependencyPolicy.java`

The original v1 shape remains accepted unchanged for existing releases. v2 uses a `packageManifest` runtime envelope plus explicit `capabilities` and `subscriptions`; its capability and event names are checked against Core's closed registries. The Marketplace validators preserve Core's structural limits and validate nested package metadata and every OCI artifact. Publisher validation passes the manifest's minimum supported Core version as a syntax/range fixture and declared dependencies as a structural fixture only. Core must still validate the real customer version, trusted registry, installed dependencies, configuration and lifecycle at install time. Manifest contract v2 is not a REST API version: all Core endpoints remain `/api/v1` until the first production release.

Core Docker runtime starts the primary image and sidecars in declared dependency order, checks each health path, persists declared volumes and enforces image digests and resource boundaries. `ExtensionRuntimeController` uses an internal controller protocol. Marketplace does not start or invoke plugin containers.

## Versioned Core management routes

Core's extension management and Marketplace proxy controllers are versioned under `/api/v1`. Core imports v1 manifests through `/api/v1/admin/extensions/register` and v2 envelopes through `/api/v1/admin/extensions/register-v2`; both remain one development REST surface. The internal extension-controller routes under `/internal/v1/extensions/**` are a separate private runtime protocol. Marketplace does not call legacy unversioned aliases.

## Template review

`stripe-payments`, `swiss-post`, `opensearch-search`, and `observability` templates use the same `manifestVersion: "1"` structure. Their image fields contain `REPLACE_WITH_PUBLISHED_IMAGE_DIGEST`; these are build/publish templates and not valid distributable release references. OpenSearch has one sidecar; Observability declares four and includes startup dependencies and persistent paths. The Marketplace rejects placeholders and verifies all image descriptors in Zot.

There is a concrete validator conflict in the pinned Core sources: `opensearch-search.template.json` declares `/usr/share/opensearch/data` as a persistent sidecar path, while `PluginManifestValidator` accepts only `/data/...`, `/var/lib/...`, or `/prometheus`. Marketplace intentionally preserves the pinned validator and rejects that unmodified template; the other three Core templates pass the Marketplace contract test after substituting syntactically valid digest references. This is not silently normalized because Core would apply the same validator during installation. Before publishing an OpenSearch release, Core must change the template path to an allowed path (and confirm the image supports it) or the Core validator must deliberately widen its allowlist, followed by a new Core pin and compatibility review.

## Executable reference fixture

`examples/reference-plugin` is a small independent implementation of the observed Core v1 HTTP protocol. The Docker acceptance builds releases `1.2.0` and `1.3.0`, pushes and publishes digest-pinned main and companion OCI artifacts, then anonymously pulls both services. It runs the v1.2.0 image in a restricted container and checks health, diagnostics, missing-token rejection, authenticated invocation, and deterministic idempotent retry. When `ARTIO_CORE_PATH` points to the pinned source checkout, acceptance passes Marketplace-published digests to isolated Core Docker runtime and catalog/API integration tests; it does not connect to a deployed customer Core. The Marketplace CI intentionally does not invoke Core's separate packaged-controller integration test: GitHub run `38906863505` against the older Core pin failed in `PackagedExtensionControllerIntegrationTests.packagedControllerAuthenticatesAndManagesPrivateBundleWithoutCommerceServices` at Core test line 146 with a `NullPointerException` while dereferencing bundle-health, even though the Docker runtime and Marketplace catalog/API tests in that same invocation passed. That separate test is an unresolved Core-owned blocker and is not counted as a Marketplace pass. Core was not modified.

The full Backoffice install/configure/enable/invocation flow is verified for the Marketplace v1 reference release. The local v2 Docker acceptance retrieved the exact v2 envelope through Core's Marketplace proxy and registered/uninstalled it at `/api/v1/admin/extensions/register-v2`; Core's v2 persistence/routing acceptance and the real Docker runtime acceptance also pass independently. Direct installation and invocation of that Marketplace-retrieved v2 release in one live Core stack remains open. Core must continue to run its normal version, registry-trust, dependency, configuration and lifecycle checks for every customer installation.

### Local Core development prerequisites

Core's `ARTIO_EXTENSION_TRUSTED_REGISTRIES` defaults to empty. For a host-run development Core consuming the local Marketplace registry, configure `ARTIO_EXTENSION_TRUSTED_REGISTRIES=localhost:5000` (or the exact public registry host in the returned manifest). Because the local Compose profile uses plain HTTP, Docker Engine must also have that host in its `insecure-registries` configuration and be restarted. Do not use this HTTP exception for a remotely reachable deployment. If Core runs inside a container or VM, `localhost` refers to that network namespace; use the host's reachable registry name consistently in Core trust settings, image references, and the Marketplace allowlist.

The missing development smoke is: obtain the exact manifest from Marketplace, call Core's `/api/v1/admin/extensions/validate`, register it, then call install and verify the Docker runtime pulls the digest from the trusted local Zot. This sequence has **not** been run against a live Core. CI's pinned runtime/controller/catalog tests are narrower and do not substitute for it. The Core checkout at `D:\Development\Projects\Artio` was already dirty during this review and must not be used as a disposable test target; use a clean isolated checkout or user-provided Core test environment.

## Compatibility verification

CI checks out the Core repository at the exact pin and builds isolated runtime/controller fixtures from that checkout; there is no standing Core process or Marketplace-to-Core client connection. Contract changes require an explicit pin change, schema/validator review, fixture updates and tests for both v1 and v2. The PostgreSQL catalog integration test covers nested v2 Core-version and geographic filtering and verifies the returned compatibility bounds/dependencies. Marketplace does not depend on the whole Core build or Spring application.
