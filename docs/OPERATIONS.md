# Operations

## Health and diagnostics

Spring Actuator health routes are `/actuator/health/liveness` and `/actuator/health/readiness`; readiness includes application readiness, PostgreSQL, and Zot. These are operational endpoints, not versioned Marketplace REST resources. Keep them behind trusted network policy. Logs go to container stdout/stderr and include Spring plus supervised Zot output.

```bash
docker compose --env-file .env -f deployment/compose.yaml ps
docker compose --env-file .env -f deployment/compose.yaml logs --tail=200 marketplace
docker compose --env-file .env -f deployment/compose.yaml logs --tail=100 postgres
```

A failed readiness check means do not route traffic to the instance. If Zot exits, the supervisor terminates the application/container so orchestration can restart it. Investigate disk exhaustion, secret readability for UID 10001, invalid Zot config, database reachability, and migration errors from logs.

## Build and automated checks

JDK 25 and Gradle 9.8.1 are required. Run `./gradlew clean build`; run Docker acceptance separately using the Compose setup and included verification scripts. Record actual command output and environment when reporting a release; network or Docker blockers must be reported as not run.

CI applies Flyway V1 through V3 to an empty PostgreSQL database. `scripts/test-v3-migration.sh` also upgrades a disposable V1 database with ambiguous legacy revocation data, checks the resulting `REVIEW_REQUIRED` state and migration audit, and verifies that changing staged catalog metadata clears the two-party publication approval. Before production upgrades, take a consistent PostgreSQL/Zot/media backup and rehearse restore using [backup and restore](BACKUP_RESTORE.md). V2 intentionally quarantines ambiguous legacy plugin revocations; review these records before making them visible or publishable. V3 requires new releases to receive a distinct admin approval bound to manifest and catalog hashes. Do not edit an already applied migration or attempt a schema downgrade as rollback. Restore the pre-upgrade backup if an application rollback is needed.

The Docker acceptance script verifies anonymous OCI reads and denied writes, digest-pinned main/sidecar publication, role separation, state transitions, SemVer/Core compatibility, moderation/revocation, public proxy boundaries, direct management endpoint denial, rate limits, and a restricted reference plugin while Marketplace is stopped. It uses a unique Compose project and removes test-only containers/images on exit. The optional backup/restore drill requires `MARKETPLACE_RESTORE_DRILL=1` and an isolated `MARKETPLACE_COMPOSE_PROJECT`; it destroys only that project's volumes.

## Release checklist

1. Review source changes and generated dependency/image pins.
2. Run Gradle build and Docker-backed acceptance against a clean disposable environment.
3. Back up before schema/runtime rollout; check Flyway logs and readiness after startup.
4. Publish a test plugin, verify public catalog and real image pull from a separate Docker client, then unlist it.
5. Verify backup age and registry/media/PostgreSQL capacity.
6. Record commit, image digest, migration version, and rollback/restore reference.

## Recovery

See [backup and restore](BACKUP_RESTORE.md). Do not edit published release rows manually: the database prevents mutation of immutable release content. Use unlist/revoke controls and retain OCI artifacts needed for rollback or consumer installs.
