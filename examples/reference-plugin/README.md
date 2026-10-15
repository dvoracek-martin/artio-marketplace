# Artio reference extension

This tiny HTTP service is an executable fixture for Marketplace and Artio Core runtime acceptance. It implements the pinned Core controller contract: unauthenticated `/health` and `/diagnostics` probes plus authenticated `POST /v1/invoke` with protocol version 1, bearer token, and required idempotency key. Invocation has no external side effects and returns a deterministic echo response, so repeated requests are safe.

Build reproducibly from the pinned Python base image:

```powershell
docker build --platform linux/amd64 --build-arg ARTIO_REFERENCE_VERSION=1.2.0 -t artio-reference:1.2.0 .
```

`docker-acceptance.sh` builds two versions and a companion image from this source, pushes them to the test Zot registry, publishes both releases through the publisher API, and pulls their immutable digests anonymously. Core compatibility still depends on the Core-side checks described in [the compatibility record](../../docs/CORE_COMPATIBILITY.md).
