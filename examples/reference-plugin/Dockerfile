FROM python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4

ARG ARTIO_REFERENCE_VERSION=1.0.0
ENV ARTIO_REFERENCE_VERSION=${ARTIO_REFERENCE_VERSION}
WORKDIR /opt/artio-extension
RUN mkdir -p /var/lib/artio-reference && chown 10001:10001 /var/lib/artio-reference
COPY --chown=10001:10001 server.py /opt/artio-extension/server.py
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["python", "/opt/artio-extension/server.py"]
