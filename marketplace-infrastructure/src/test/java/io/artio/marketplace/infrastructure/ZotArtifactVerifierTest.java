package io.artio.marketplace.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZotArtifactVerifierTest {
  private HttpServer registry;
  private String imageDigest;
  private String imageReference;
  private ZotArtifactVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    byte[] imageManifest =
        "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
            .concat(
                "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":0,\"digest\":\"sha256:")
            .concat("0".repeat(64))
            .concat("\"},\"layers\":[]}")
            .getBytes(StandardCharsets.UTF_8);
    imageDigest =
        "sha256:"
            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(imageManifest));
    imageReference = "localhost:5000/public/plugins/example@" + imageDigest;

    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registry.createContext(
        "/v2/public/plugins/example/manifests/" + imageDigest,
        exchange -> {
          exchange.getResponseHeaders().add("Docker-Content-Digest", imageDigest);
          exchange
              .getResponseHeaders()
              .add("Content-Type", "application/vnd.oci.image.manifest.v1+json");
          exchange.sendResponseHeaders(200, imageManifest.length);
          exchange.getResponseBody().write(imageManifest);
          exchange.close();
        });
    registry.start();
    verifier =
        new ZotArtifactVerifier(
            "localhost:5000",
            URI.create("http://127.0.0.1:" + registry.getAddress().getPort()),
            "public/plugins/");
  }

  @AfterEach
  void tearDown() {
    registry.stop(0);
  }

  @Test
  void verifiesDigestPinnedMainAndEverySidecarAgainstRegistry() throws Exception {
    var manifest =
        new ObjectMapper()
            .readTree(
                """
                    {"image":"%s","services":[{"image":"%s"}]}
                    """
                    .formatted(imageReference, imageReference));

    assertTrue(verifier.verify("localhost:5000", manifest.toString()).isEmpty());
  }

  @Test
  void rejectsTagOnlyUnknownHostAndUnapprovedRepositoryBeforeNetworkAccess() throws Exception {
    var mapper = new ObjectMapper();
    var tagOnly = mapper.readTree("{\"image\":\"localhost:5000/public/plugins/example:1.0.0\"}");
    var unknownHost =
        mapper.readTree(
            "{\"image\":\"evil.example/public/plugins/example@sha256:%s\"}"
                .formatted("a".repeat(64)));
    var unapprovedRepository =
        mapper.readTree(
            "{\"image\":\"localhost:5000/private/example@sha256:%s\"}".formatted("a".repeat(64)));

    assertEquals(1, verifier.verify("localhost:5000", tagOnly.toString()).size());
    assertTrue(
        verifier
            .verify("localhost:5000", unknownHost.toString())
            .getFirst()
            .contains("configured public registry"));
    assertTrue(
        verifier
            .verify("localhost:5000", unapprovedRepository.toString())
            .getFirst()
            .contains("approved repository prefixes"));
  }

  @Test
  void rejectsMissingSidecarArtifact() throws Exception {
    var manifest =
        new ObjectMapper()
            .readTree(
                """
                    {"image":"%s","services":[{"image":"localhost:5000/public/plugins/example@sha256:%s"}]}
                    """
                    .formatted(imageReference, "a".repeat(64)));

    assertEquals(1, verifier.verify("localhost:5000", manifest.toString()).size());
  }
}
