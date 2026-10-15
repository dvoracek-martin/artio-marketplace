FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon :marketplace-server:bootJar

FROM ghcr.io/project-zot/zot@sha256:6b69512c00dceaad05b1144e6079aac6aa7309d7fd200f9947ecb1de09cf48c8 AS zot

FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
ENV HOME=/tmp LANG=C.UTF-8
WORKDIR /app
COPY --from=build --chown=10001:10001 /src/marketplace-server/build/libs/marketplace-server-0.1.0-SNAPSHOT.jar /app/marketplace.jar
COPY --from=zot --chown=10001:10001 /usr/local/bin/zot-linux-amd64 /usr/local/bin/zot
COPY --chown=10001:10001 deployment/zot/config.json /etc/zot/config.json
RUN mkdir -p /var/lib/zot /var/lib/artio-marketplace/assets /tmp && chown -R 10001:10001 /var/lib/zot /var/lib/artio-marketplace /etc/zot /app /tmp && chmod 0555 /usr/local/bin/zot
USER 10001:10001
EXPOSE 8080 5000
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=4s --start-period=45s --retries=3 CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Djava.io.tmpdir=/tmp", "-jar", "/app/marketplace.jar"]
