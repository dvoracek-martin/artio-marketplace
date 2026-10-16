# Artio Core compatibility record

## Source pin

- Repository: `https://github.com/dvoracek-martin/artio`
- Branch at implementation start: `main`
- Commit: `80b8f799e1ab3329844e0fa564f0f373b1ad4c07` (`feat(extensions): install verified Marketplace releases`)
- This public Core pin includes the v5.1 capability-envelope v2 contract, signed release-descriptor verification, durable registered-plugin installation jobs, signed Marketplace advisory verification with local exact-digest enforcement, encrypted credentials for explicitly trusted private OCI registries, and the server-side official-release-to-job path. Marketplace CI checks out this exact commit in an isolated workspace and does not modify Core.
- Core Spring Boot: 4.1.1 (root Gradle plugin declaration); Gradle wrapper: 9.8.1.

## Runtime manifest contracts observed at the pinned commit

`marketplace-contract` retains the Core v1 runtime types and adds the v5.1 v2 envelope types/validators from this Core pin:

- `artio-extensions/.../PluginManifest.java`
- `artio-extensions/.../PluginManifestValidator.java`
- `artio-extensions/.../PluginDependencyPolicy.java`

The original v1 shape remains accepted unchanged for existing releases. v2 uses a `packageManifest` runtime envelope plus explicit `capabilities` and `subscriptions`; its capability and event names are checked against Core's closed registries. The Marketplace validators preserve Core's structural limits and validate nested package metadata and every OCI artifact. Publisher validation passes the manifest's minimum supported Core version as a syntax/range fixture and declared dependencies as a structural fixture only. Core must still validate the real customer version, trusted registry, installed dependencies, configuration and lifecycle at install time. Manifest contract v2 is not a REST API version: all Core endpoints remain `/api/v1` until the first production release.

Core Docker runtime starts the primary image and sidecars in declared dependency order, checks each health path, persists declared volumes and enforces image digests and resource boundaries. `ExtensionRuntimeController` uses an internal controller protocol. Marketplace does not start or invoke plugin containers.

## Versioned Core management routes

Core's extension management and Marketplace proxy controllers are versioned under `/api/v1`. For an official install, Backoffice sends only `pluginId`, `version`, and an idempotency key to `POST /api/v1/admin/extensions/marketplace/plugins/{pluginId}/releases/{version}/installations`. Core fetches and verifies the signed release descriptor itself, validates local policy, atomically records release provenance and a digest-bound `MARKETPLACE` job, then its durable worker pulls the pinned primary and sidecar images. The browser does not relay a manifest as evidence of official origin. Manifest registration remains available for custom/private workflows. The internal extension-controller routes under `/internal/v1/extensions/**` are a separate private runtime protocol. Marketplace does not call legacy unversioned aliases or access customer Docker.

## Live signed-advisory acceptance

Marketplace CI run `38941144533` passed with Core commit `eb3d79e355d1db9b150d64bfee93ec61a19d3802`. It provisioned a feed-specific Ed25519 key, revoked `marketplace.reference@1.2.9`, then had Core fetch, verify and persist that live signed snapshot and its permanent image-digest ban. The same run passed Marketplace manifest import and the selected restricted Docker runtime tests; the fixture-gated egress test remains skipped. The published advisory feed deduplicates image digests when a release uses the same digest for multiple roles, matching Core's strict signed-inventory validation.

Core commit `9edd334a8cdd55b17f11b4af84304fe0f190c571` adds a focused verifier test for signing-key rotation: both old and new release signatures pass while their keys overlap, and the retired key is rejected after it is removed from the trust set. Marketplace tests exercise a generated-key rotation in PostgreSQL for release descriptors and through the signed advisory snapshot service. The keys are generated per test; no production private key or external credential is required.

## Publication recovery acceptance

Marketplace CI run `38943007707` passed `PublicationRecoveryPostgresIntegrationTest` against the current single V1 schema snapshot. It injects a partial OCI copy failure and retries the same approved release, recovers an expired promotion reservation after process interruption, and races a plugin moderation suspension against publication finalization. Each failed attempt remains retryable and cannot mark the release `PUBLISHED` until the full promotion and final moderation check pass. The same workflow passed the live Zot/Marketplace acceptance, backup/restore smoke pull, vulnerability scan and ephemeral Compose cleanup. No incremental schema or V1-to-V2 upgrade migration is part of this gate.

The pinned Core also encrypts per-host private registry credentials in its single development V1 schema. Credential management is limited to the configured `ARTIO_EXTENSION_TRUSTED_REGISTRIES` allowlist and recent MFA-protected Backoffice administration. The exact Core pin above passed the local signed descriptor → idempotent install job → digest pull → worker completion acceptance. The Marketplace CI run for this pin remains the final cross-repository compatibility gate.

The 2026-10-16 local integration follow-up provisions a separate ephemeral Ed25519 advisory key, matching the feed's independent signing trust. The successful isolated run published a signed Marketplace release, verified and revoked a signed advisory, exercised the proxy, and then had Core fetch and verify the immutable descriptor, create and replay a durable idempotent installation job, pull the exact OCI digest, and complete the job through the real Docker worker. PostgreSQL/API, Docker runtime, advisory ingestion, and Core unit tests passed. Core's duplicate `artio.storefront-url` YAML key found by an earlier run was removed. The interrupted prior run's exact project had no containers, volumes, or network when inspected; the successful run removed its own containers, network, three volumes, and temporary images. Core's current development database remains a single V1 schema snapshot.

## Template review

`stripe-payments`, `swiss-post`, `opensearch-search`, and `observability` templates use the same `manifestVersion: "1"` structure. Their image fields contain `REPLACE_WITH_PUBLISHED_IMAGE_DIGEST`; these are build/publish templates and not valid distributable release references. OpenSearch has one sidecar; Observability declares four and includes startup dependencies and persistent paths. The Marketplace rejects placeholders and verifies all image descriptors in Zot.

There is a concrete validator conflict in the pinned Core sources: `opensearch-search.template.json` declares `/usr/share/opensearch/data` as a persistent sidecar path, while `PluginManifestValidator` accepts only `/data/...`, `/var/lib/...`, or `/prometheus`. Marketplace intentionally preserves the pinned validator and rejects that unmodified template; the other three Core templates pass the Marketplace contract test after substituting syntactically valid digest references. This is not silently normalized because Core would apply the same validator during installation. Before publishing an OpenSearch release, Core must change the template path to an allowed path (and confirm the image supports it) or the Core validator must deliberately widen its allowlist, followed by a new Core pin and compatibility review.

## Executable reference fixture

`examples/reference-plugin` is a small independent implementation of the observed Core v1 HTTP protocol. The Docker acceptance builds releases `1.2.0` and `1.3.0`, pushes and publishes digest-pinned main and companion OCI artifacts, then anonymously pulls both services. It runs the v1.2.0 image in a restricted container and checks health, diagnostics, missing-token rejection, authenticated invocation, and deterministic idempotent retry. When `ARTIO_CORE_PATH` points to the pinned source checkout, acceptance passes Marketplace-published digests to isolated Core Docker runtime and catalog/API integration tests; it does not connect to a deployed customer Core. The Marketplace CI intentionally does not invoke Core's separate packaged-controller integration test: GitHub run `38906863505` against the older Core pin failed in `PackagedExtensionControllerIntegrationTests.packagedControllerAuthenticatesAndManagesPrivateBundleWithoutCommerceServices` at Core test line 146 with a `NullPointerException` while dereferencing bundle-health, even though the Docker runtime and Marketplace catalog/API tests in that same invocation passed. That separate test is an unresolved Core-owned blocker and is not counted as a Marketplace pass. Core was not modified.

The full Backoffice install/configure/enable/invocation flow is verified for the Marketplace v1 reference release. The local v2 Docker acceptance retrieved the exact v2 envelope through Core's Marketplace proxy and registered/uninstalled it at `/api/v1/admin/extensions/register-v2`; Core's v2 persistence/routing acceptance and the real Docker runtime acceptance also pass independently. Direct installation and invocation of that Marketplace-retrieved v2 release in one live Core stack remains open. Core must continue to run its normal version, registry-trust, dependency, configuration and lifecycle checks for every customer installation.

### Local Core development prerequisites

Core's `ARTIO_EXTENSION_TRUSTED_REGISTRIES` defaults to empty. For a host-run development Core consuming the local Marketplace registry, configure `ARTIO_EXTENSION_TRUSTED_REGISTRIES=localhost:5000` (or the exact public registry host in the returned manifest). Because the local Compose profile uses plain HTTP, Docker Engine must also have that host in its `insecure-registries` configuration and be restarted. Do not use this HTTP exception for a remotely reachable deployment. If Core runs inside a container or VM, `localhost` refers to that network namespace; use the host's reachable registry name consistently in Core trust settings, image references, and the Marketplace allowlist.

The live development smoke is now closed by the isolated Core/Marketplace acceptance above. That test uses a development fixture and the local Zot address routed through `host.docker.internal`; production deployments must still configure the exact trusted registry host in `ARTIO_EXTENSION_TRUSTED_REGISTRIES` and use TLS. CI's pinned runtime/controller/catalog tests remain independent checks rather than substitutes for the live signed descriptor and durable install acceptance.

## Compatibility verification

CI checks out the Core repository at the exact pin and builds isolated runtime/controller fixtures from that checkout; there is no standing Core process or Marketplace-to-Core client connection. Contract changes require an explicit pin change, schema/validator review, fixture updates and tests for both v1 and v2. The PostgreSQL catalog integration test covers nested v2 Core-version and geographic filtering and verifies the returned compatibility bounds/dependencies. Marketplace does not depend on the whole Core build or Spring application.
