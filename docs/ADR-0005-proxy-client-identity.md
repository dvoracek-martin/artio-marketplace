# ADR-0005: Resolve client addresses only through trusted proxies

**Status:** Accepted  
**Date:** 2026-10-16

## Decision

The socket peer is authoritative unless it matches an explicitly configured trusted exact address or CIDR. For trusted peers only, the rate limiter walks `X-Forwarded-For` from right to left and discards configured trusted hops to find the first untrusted client. The sample Nginx edge overwrites inbound `X-Forwarded-For` with `$remote_addr`. Arbitrary forwarding headers are ignored from untrusted peers.

Public and publisher requests use separate per-client windows and global admission ceilings. The in-process bucket table has a hard size cap, expiry and LRU eviction. Multi-replica deployments require shared media and a distributed rate-limiter implementation before scaling API replicas.

## Consequences and limits

Deployment operators must configure the exact proxy networks that can reach the API and prevent direct public access to application ports. No private network, Docker subnet or Cloudflare header is trusted automatically. Without a trusted proxy entry, clients behind that proxy intentionally share the socket-peer bucket; replace the `.env.example` placeholder with the exact proxy peer address/CIDR observed by the app. The example proxy integration tests exercise public routing, admin network denial and actuator denial; proxy-chain unit tests cover right-to-left resolution, spoofed-leftmost input, bucket-cap eviction, and CIDR matching.
