package io.artio.marketplace.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZotArtifactPromoterTest {
  private HttpServer registry;
  private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
  private final Map<String, byte[]> manifests = new ConcurrentHashMap<>();
  private final Map<String, String> manifestMediaTypes = new ConcurrentHashMap<>();
  private byte[] manifest;
  private String manifestDigest;
  private String configDigest;
  private String layerDigest;
  private ZotArtifactPromoter promoter;
  private int stagedReads;
  private int publishedManifests;
  private int publicWrites;
  private boolean authorized = true;

  @BeforeEach
  void setUp() throws Exception {
    byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] layer = "test-layer-data".getBytes(StandardCharsets.UTF_8);
    configDigest = digest(config);
    layerDigest = digest(layer);
    blobs.put(configDigest, config);
    blobs.put(layerDigest, layer);
    manifest =
        ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":2,\"digest\":\""
                + configDigest
                + "\"},\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\",\"size\":15,\"digest\":\""
                + layerDigest
                + "\"}]}")
            .getBytes(StandardCharsets.UTF_8);
    manifestDigest = digest(manifest);
    manifests.put(manifestDigest, manifest);
    manifestMediaTypes.put(manifestDigest, "application/vnd.oci.image.manifest.v1+json");
    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registry.createContext(
        "/v2/",
        exchange -> {
          if (!"Basic cHJvbW90ZXI6c2VjcmV0"
              .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            authorized = false;
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
          }
          String path = exchange.getRequestURI().getPath();
          String method = exchange.getRequestMethod();
          if (method.equals("GET") && path.contains("/manifests/")) {
            String requestedDigest = path.substring(path.lastIndexOf('/') + 1);
            byte[] content = manifests.get(requestedDigest);
            if (content == null) exchange.sendResponseHeaders(404, -1);
            else {
              if (path.startsWith("/v2/staging/")) stagedReads++;
              exchange
                  .getResponseHeaders()
                  .add("Content-Type", manifestMediaTypes.get(requestedDigest));
              exchange.getResponseHeaders().add("Docker-Content-Digest", requestedDigest);
              exchange.sendResponseHeaders(200, content.length);
              exchange.getResponseBody().write(content);
            }
          } else if (method.equals("GET")
              && path.startsWith("/v2/staging/plugins/example/blobs/")) {
            byte[] content = blobs.get(path.substring(path.lastIndexOf('/') + 1));
            if (content == null) exchange.sendResponseHeaders(404, -1);
            else {
              exchange.sendResponseHeaders(200, content.length);
              exchange.getResponseBody().write(content);
            }
          } else if (method.equals("POST")
              && path.equals("/v2/public/plugins/example/blobs/uploads/")) {
            exchange
                .getResponseHeaders()
                .add("Location", "/v2/public/plugins/example/blobs/uploads/1");
            exchange.sendResponseHeaders(202, -1);
          } else if (method.equals("PUT")
              && path.equals("/v2/public/plugins/example/blobs/uploads/1")) {
            String digest = exchange.getRequestURI().getQuery().substring("digest=".length());
            byte[] data = exchange.getRequestBody().readAllBytes();
            if (!digest(data).equals(digest)) exchange.sendResponseHeaders(400, -1);
            else {
              blobs.put(digest, data);
              publicWrites++;
              exchange.sendResponseHeaders(201, -1);
            }
          } else if (method.equals("PUT") && path.contains("/public/plugins/example/manifests/")) {
            String requestedDigest = path.substring(path.lastIndexOf('/') + 1);
            byte[] data = exchange.getRequestBody().readAllBytes();
            if (!digest(data).equals(requestedDigest)) exchange.sendResponseHeaders(400, -1);
            else {
              exchange.getResponseHeaders().add("Docker-Content-Digest", requestedDigest);
              publishedManifests++;
              exchange.sendResponseHeaders(201, -1);
            }
          } else {
            exchange.sendResponseHeaders(404, -1);
          }
          exchange.close();
        });
    registry.start();
    promoter =
        new ZotArtifactPromoter(
            "localhost:5000",
            URI.create("http://127.0.0.1:" + registry.getAddress().getPort()),
            "promoter",
            "secret");
  }

  @AfterEach
  void tearDown() {
    registry.stop(0);
  }

  @Test
  void copiesEveryImageBlobThenPreservesTheApprovedManifestDigest() {
    promoter.promote(
        "{\"packageManifest\":{\"image\":\"localhost:5000/public/plugins/example@"
            + manifestDigest
            + "\",\"services\":[{\"image\":\"localhost:5000/public/plugins/example@"
            + manifestDigest
            + "\"}]}}");

    assertEquals(1, stagedReads, "duplicate main/sidecar identity should be copied once");
    assertEquals(2, publicWrites, "config and layer blobs should be uploaded");
    assertTrue(authorized);
  }

  @Test
  void copiesNestedPlatformManifestsAndPreservesTheIndexDigest() throws Exception {
    byte[] armConfig = "{\"architecture\":\"arm64\"}".getBytes(StandardCharsets.UTF_8);
    String armConfigDigest = digest(armConfig);
    blobs.put(armConfigDigest, armConfig);
    byte[] armManifest =
        ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":"
                + armConfig.length
                + ",\"digest\":\""
                + armConfigDigest
                + "\"},\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\",\"size\":15,\"digest\":\""
                + layerDigest
                + "\"}]}")
            .getBytes(StandardCharsets.UTF_8);
    String armManifestDigest = digest(armManifest);
    manifests.put(armManifestDigest, armManifest);
    manifestMediaTypes.put(armManifestDigest, "application/vnd.oci.image.manifest.v1+json");
    byte[] index =
        ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.index.v1+json\",\"manifests\":["
                + "{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"digest\":\""
                + manifestDigest
                + "\",\"size\":"
                + manifest.length
                + ",\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}},"
                + "{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"digest\":\""
                + armManifestDigest
                + "\",\"size\":"
                + armManifest.length
                + ",\"platform\":{\"architecture\":\"arm64\",\"os\":\"linux\"}}]}")
            .getBytes(StandardCharsets.UTF_8);
    String indexDigest = digest(index);
    manifests.put(indexDigest, index);
    manifestMediaTypes.put(indexDigest, "application/vnd.oci.image.index.v1+json");

    promoter.promote("{\"image\":\"localhost:5000/public/plugins/example@" + indexDigest + "\"}");

    assertEquals(3, stagedReads, "the index and both platform manifests must be read");
    assertEquals(3, publishedManifests, "both child manifests and the index must be published");
    assertTrue(authorized);
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
