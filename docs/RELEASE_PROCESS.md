# Release process

1. Update docs alongside behavior and review the Core compatibility snapshot.
2. Run the JDK 25 Gradle build and the Docker-backed acceptance suite in a clean environment.
3. Review dependency and container digest changes, database migrations, security config, and generated OpenAPI/schema changes.
4. Build the application image, record its immutable digest, and deploy first to a staging stack with restored test data.
5. Verify readiness and public browse/search/detail. Stage a release with the publisher credential, review its exact manifest/catalog and image digests with the separate admin credential, approve it with a reason, then publish with the publisher credential. Verify OCI pulls by digest, audit, and unlist/revoke.
6. Confirm backup freshness and recovery procedure, then deploy production with a rollback image and restore point available.
7. Record release version, commit, image digest, migration version, test results, and deployment/rollback outcome.

Do not advertise a release as acceptance-tested when OCI, database, or Docker steps were skipped. Report external blockers and any unrun criteria explicitly.
