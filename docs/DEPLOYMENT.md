# Deployment guide

## Supported topology

The Marketplace image runs the Java 25 Spring Boot API and a supervised Zot registry in the same container. PostgreSQL 16 and a small Nginx registry gateway run separately. PostgreSQL, Zot blobs, and uploaded media each have persistent named volumes. Java is PID 1 and supervises Zot so stop signals and Zot process failure are propagated. Java, PostgreSQL, Zot, and gateway images are pinned by digest.

Compose binds API port 8080 and registry-gateway ports 5000 and 5001 to host loopback. Port 5000 returns `200` only for the unauthenticated `GET /v2/` ping, then proxies public repository operations to Zot. Port 5001 passes the ping through to Zot so publisher Docker clients receive the normal Basic-auth challenge and send credentials on pushes. Both listeners address the same Zot storage and Zot enforces all repository ACLs. Plugin manifests and customer Core configuration use only the public hostname configured as `MARKETPLACE_REGISTRY_HOST`; publishers push through the separately configured publisher host/port. Do not proxy uploads through the Marketplace API or let the API bypass Zot ACLs. Put a maintained HTTPS reverse proxy or load balancer in front for remote access. Route Marketplace API to 8080, public OCI traffic to the public registry virtual host, and publisher writes to a publisher-only virtual host. Set `MARKETPLACE_REGISTRY_HOST` to the canonical public DNS host and restrict publisher ingress to trusted operator networks. OCI reference host validation is exact; use one canonical public name.

For an external HTTPS ingress, see [the example Nginx routes](../deployment/reverse-proxy.example/marketplace.conf). The public registry virtual host preserves the `/v2/` ping behavior; the separate publisher virtual host preserves Zot's Basic challenge and is IP-restricted in the example.

## Configuration and first startup

See [.env.example](../.env.example) and [README](../README.md#run-locally-with-docker) for local setup. Production should use an orchestrator secret store rather than a checked-in or broadly readable `.env`; provide equivalent environment variables and mount the Zot htpasswd secret read-only. Database credentials, publisher token hash, and registry password are distinct.

Start with `docker compose --env-file .env -f deployment/compose.yaml up --build -d`. Inspect with `docker compose --env-file .env -f deployment/compose.yaml ps` and readiness at `/actuator/health/readiness`. Flyway applies versioned schema migrations at startup.

## Production checklist

- Use HTTPS, HSTS, strict host routing, and request size/time limits at the proxy. Prevent direct Internet access to container ports.
- Store publisher and Zot credentials in a secret manager and rotate them. Limit the shared publisher credential to trusted operators.
- Set resource limits based on measured image sizes, database growth, and upload traffic; Compose values are local defaults, not capacity guidance.
- Configure PostgreSQL PITR or scheduled encrypted backups, registry/media backups, restore drills, off-host copies, and retention.
- Monitor readiness, disk use for each persistent volume, PostgreSQL connections, registry/API errors, and backup age. Alert on Zot exit and full disks.
- Review registry retention and garbage collection before enabling deletion; published image manifests must remain pullable.
- Review [Core compatibility](CORE_COMPATIBILITY.md); the Core management route migration is an upstream integration dependency.

## Scaling

The first release is a single Marketplace writer. PostgreSQL and OCI/media storage are durable, but uploaded media is stored on a local volume. Replicas need shared storage or an object-store adapter first. Zot is supervised in the service container for this profile; separating it requires changing endpoint/process lifecycle configuration and revalidating health behavior.
