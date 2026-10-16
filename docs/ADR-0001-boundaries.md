# ADR 0001: Keep Marketplace separate from Core and Zot

**Status:** accepted. **Date:** 2026-10-15.

The Marketplace is a distinct Java service and repository. Its framework-free contract module preserves Core's v1 runtime manifest/dependency rules and implements the v5.1 v2 capability-envelope contract against Core's closed registries; the current source pin and the published CI compatibility pin are recorded in `CORE_COMPATIBILITY.md`. Zot remains an upstream OCI registry process in the delivery container, not a Java registry implementation. The Marketplace API owns only catalog metadata, publisher workflow, media and discovery. Artio Core retains registration, configuration, install, enable, runtime, invocation, update, rollback and uninstall.

This avoids a second plugin runtime and prevents marketplace availability from becoming a runtime dependency for already-installed customer plugins.
