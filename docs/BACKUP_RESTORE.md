# Backup and restore

Marketplace state lives in three independently persisted stores: PostgreSQL (`postgres_data`), Zot OCI blobs (`zot_data`), and uploaded catalog media (`catalog_assets`). A useful backup set must cover all three at a coordinated point in time, plus the exact deployment image/configuration and secrets needed for recovery. PostgreSQL contains media metadata and content digests; losing either the media volume or the database breaks media delivery.

## Backup

Use encrypted off-host storage and a tested retention policy. A basic PostgreSQL logical backup is:

```bash
docker compose --env-file .env -f deployment/compose.yaml exec -T postgres pg_dump -U artio_marketplace -Fc artio_marketplace > marketplace.pgdump
```

Back up `zot_data` and `catalog_assets` with a filesystem/snapshot tool appropriate for Docker named volumes. Quiesce writes during file-volume capture or use an atomic storage snapshot; record PostgreSQL dump time and volume snapshot IDs together. Also save the Marketplace container image digest, `.env` values in the secret manager, Zot htpasswd secret, reverse-proxy configuration, and certificate/secret references. Never check backup credentials or secret values into Git.

## Restore

1. Provision a clean host with Docker and the same or compatible Compose deployment version.
2. Restore the three persistent stores into their expected volumes and restore credentials from the secret manager.
3. Start PostgreSQL and Marketplace. If the restored database is newer than the application schema supports, deploy the matching application image; do not downgrade by editing migration history.
4. Check readiness, verify a known published plugin's catalog detail and media, and pull its OCI digest from Zot using a separate client.
5. Record recovery point, image/migration versions, and any lost writes since the backup.

Perform regular isolated restore drills. Backups are not considered valid until the database, metadata, media, and OCI artifact checks all pass.
