# Deployment guide

## Supported topology

The Marketplace image runs the Java 25 Spring Boot API and a supervised Zot registry in the same container. PostgreSQL 16 and a small Nginx registry gateway run separately. PostgreSQL, Zot blobs, and uploaded media each have persistent named volumes. Java is PID 1 and supervises Zot so stop signals and Zot process failure are propagated. Java, PostgreSQL, Zot, and gateway images are pinned by digest.

Compose binds API port 8080, management port 8081, and registry-gateway ports 5000 and 5001 to host loopback. Management is a separate server port and the example Internet-facing proxy returns 404 for actuator routes. Port 5000 returns `200` only for the unauthenticated `GET /v2/` ping, then proxies public repository operations to Zot. Port 5001 passes the ping through to Zot so publisher Docker clients receive the normal Basic-auth challenge and send credentials on pushes. Both listeners address the same Zot storage and Zot enforces all repository ACLs. Plugin manifests and customer Core configuration use only the public hostname configured as `MARKETPLACE_REGISTRY_HOST`; publishers push through the separately configured publisher host/port. Do not proxy uploads through the Marketplace API or let the API bypass Zot ACLs. Put a maintained HTTPS reverse proxy or load balancer in front for remote access. Route Marketplace API to 8080, public OCI traffic to the public registry virtual host, and publisher writes to a publisher-only virtual host. Restrict `/api/v1/admin/` to the trusted operator network. OCI reference host validation is exact; repository paths must match `MARKETPLACE_REGISTRY_ALLOWED_REPOSITORY_PREFIXES`.

For an external HTTPS ingress, see [the example Nginx routes](../deployment/reverse-proxy.example/marketplace.conf). The public registry virtual host preserves the `/v2/` ping behavior; the separate publisher virtual host preserves Zot's Basic challenge and is IP-restricted in the example.

## Configuration and first startup

See [.env.example](../.env.example) and [README](../README.md#run-locally-with-docker) for local setup. Production should use an orchestrator secret store rather than a checked-in or broadly readable `.env`; provide equivalent environment variables and mount the Zot htpasswd secret read-only. Database credentials, publisher/admin/revocation token hashes, and Zot password are distinct. Store each raw API token separately from `.env`; only its SHA-256 hash belongs in the environment.

Start with `docker compose --env-file .env -f deployment/compose.yaml up --build -d`. Inspect with `docker compose --env-file .env -f deployment/compose.yaml ps` and readiness at `/actuator/health/readiness`. Flyway applies versioned schema migrations at startup.

## Production checklist

- Use HTTPS, HSTS, strict host routing, and request size/time limits at the proxy. Prevent direct Internet access to container ports.
- Replace the `MARKETPLACE_RATE_LIMIT_TRUSTED_PROXIES` placeholder with exact addresses or CIDRs for the actual proxy peers, as seen by Marketplace. Without this, clients behind one proxy share that proxy's socket-peer bucket and can collectively consume the per-client allowance. Never guess the Docker bridge address or trust a broad private range. The supplied Nginx example sanitizes XFF to `$remote_addr`; tune edge and application per-client/global limits for expected traffic.
- Store publisher, admin, revocation, and Zot credentials in a secret manager and rotate them independently. Limit the admin endpoint at the network boundary.
- Set resource limits based on measured image sizes, database growth, and upload traffic; Compose values are local defaults, not capacity guidance.
- Configure PostgreSQL PITR or scheduled encrypted backups, registry/media backups, restore drills, off-host copies, and retention.
- Monitor readiness, disk use for each persistent volume, PostgreSQL connections, registry/API errors, and backup age. Alert on Zot exit and full disks.
- Zot automatic garbage collection and publisher delete permission are disabled. Never enable deletion/GC until database references, unlisted/revoked releases, backups, restore, and digest-pull retention are coordinated and verified.
- Review [Core compatibility](CORE_COMPATIBILITY.md); configure the public registry hostname in the customer's `ARTIO_EXTENSION_TRUSTED_REGISTRIES` and retain Core's install-time validation.

## Scaling

The first release is a single Marketplace writer serving many independent Core installations; customer count does not create Marketplace-to-Core connections. The current in-process rate limiter and local media volume mean that adding API replicas requires shared media storage and a distributed rate-limit adapter first. Capacity for a specific number of clients must be established with a representative load test; this profile does not claim a measured thousands-of-clients throughput figure. PostgreSQL and OCI/media storage are durable. Zot is supervised in the service container for this profile; separating it requires changing endpoint/process lifecycle configuration and revalidating health behavior.
