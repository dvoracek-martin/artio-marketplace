# ADR 0001: Keep Marketplace separate from Core and Zot

**Status:** accepted. **Date:** 2026-10-15.

The Marketplace is a distinct Java service and repository. Its only copied Core sources are the three framework-free v1 manifest/dependency contract files pinned in `CORE_COMPATIBILITY.md`, with a source drift CI gate. Zot remains an upstream OCI registry process in the delivery container, not a Java registry implementation. The Marketplace API owns only catalog metadata, publisher workflow, media and discovery. Artio Core retains registration, configuration, install, enable, runtime, invocation, update, rollback and uninstall.

This avoids a second plugin runtime and prevents marketplace availability from becoming a runtime dependency for already-installed customer plugins.
