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

## Release checklist

1. Review source changes and generated dependency/image pins.
2. Run Gradle build and Docker-backed acceptance against a clean disposable environment.
3. Back up before schema/runtime rollout; check Flyway logs and readiness after startup.
4. Publish a test plugin, verify public catalog and real image pull from a separate Docker client, then unlist it.
5. Verify backup age and registry/media/PostgreSQL capacity.
6. Record commit, image digest, migration version, and rollback/restore reference.

## Recovery

See [backup and restore](BACKUP_RESTORE.md). Do not edit published release rows manually: the database prevents mutation of immutable release content. Use unlist/revoke controls and retain OCI artifacts needed for rollback or consumer installs.
