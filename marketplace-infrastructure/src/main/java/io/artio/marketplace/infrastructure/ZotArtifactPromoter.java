package io.artio.marketplace.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.marketplace.application.ArtifactPromoter;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Promotes an approved bundle using only Zot's OCI Distribution API and the dedicated promoter. */
@Component
public final class ZotArtifactPromoter implements ArtifactPromoter {
  private static final Pattern IMAGE_REF =
      Pattern.compile(
          "([a-zA-Z0-9.-]+(?::[0-9]{1,5})?)/(public/plugins/[a-zA-Z0-9._/-]+)@(sha256:[a-f0-9]{64})");
  private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
  private static final String ACCEPT =
      String.join(
          ",",
          "application/vnd.oci.image.index.v1+json",
          "application/vnd.oci.image.manifest.v1+json",
          "application/vnd.docker.distribution.manifest.list.v2+json",
          "application/vnd.docker.distribution.manifest.v2+json");

  private final String expectedHost;
  private final URI registry;
  private final String authorization;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper mapper = new ObjectMapper();

  public ZotArtifactPromoter(
      @Value("${marketplace.registry.public-host:localhost:5000}") String expectedHost,
      @Value("${marketplace.registry.internal-url:http://127.0.0.1:5000}") URI registry,
      @Value("${marketplace.registry.promotion-username:promoter}") String username,
      @Value("${marketplace.registry.promotion-password:}") String password) {
    if (registry.getUserInfo() != null
        || registry.getHost() == null
        || !Set.of("http", "https").contains(registry.getScheme())) {
      throw new IllegalArgumentException("Invalid configured Zot endpoint");
    }
    this.expectedHost = expectedHost.toLowerCase(java.util.Locale.ROOT);
    this.registry = registry;
    this.authorization =
        username == null || username.isBlank() || password == null || password.isBlank()
            ? ""
            : "Basic "
                + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void promote(String manifestJson) {
    try {
      JsonNode root = mapper.readTree(manifestJson);
      JsonNode packageManifest =
          root.path("packageManifest").isObject() ? root.path("packageManifest") : root;
      List<String> references = new ArrayList<>();
      references.add(packageManifest.path("image").asText(""));
      JsonNode services = packageManifest.path("services");
      if (services.isArray())
        for (JsonNode service : services) references.add(service.path("image").asText(""));
      if (references.isEmpty() || references.stream().anyMatch(String::isBlank))
        throw new IllegalArgumentException("Approved release has an incomplete image bundle");
      Set<String> copied = new HashSet<>();
      for (String reference : references) promoteImage(reference, copied);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Approved OCI bundle could not be promoted", failure);
    }
  }

  private void promoteImage(String reference, Set<String> copied) throws Exception {
    Matcher match = IMAGE_REF.matcher(reference);
    if (!match.matches() || !expectedHost.equalsIgnoreCase(match.group(1)))
      throw new IllegalArgumentException(
          "Approved image is outside the configured public registry");
    String publicRepository = match.group(2);
    String stagingRepository = "staging/" + publicRepository.substring("public/".length());
    copyManifestTree(stagingRepository, publicRepository, match.group(3), copied, 0);
  }

  private void copyManifestTree(
      String sourceRepository,
      String targetRepository,
      String digest,
      Set<String> visited,
      int depth)
      throws Exception {
    if (depth > 3)
      throw new IllegalArgumentException("OCI index nesting exceeds the supported limit");
    String identity = sourceRepository + "@" + digest;
    if (!visited.add(identity)) return;
    HttpResponse<byte[]> source =
        send(
            HttpRequest.newBuilder(uri(sourceRepository, "manifests", digest))
                .timeout(Duration.ofMinutes(2))
                .header("Accept", ACCEPT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    requireStatus(source.statusCode(), 200, "read staged OCI manifest");
    verifyDigest(digest, source.body());
    String mediaType = mediaType(source);
    JsonNode manifest = mapper.readTree(source.body());
    JsonNode children = manifest.path("manifests");
    if (children.isArray()) {
      if (children.isEmpty()) throw new IllegalArgumentException("OCI index is empty");
      for (JsonNode child : children) {
        String childDigest = child.path("digest").asText("");
        if (!DIGEST.matcher(childDigest).matches())
          throw new IllegalArgumentException("OCI index contains an invalid manifest digest");
        copyManifestTree(sourceRepository, targetRepository, childDigest, visited, depth + 1);
      }
    } else {
      copyBlob(sourceRepository, targetRepository, manifest.path("config").path("digest").asText());
      JsonNode layers = manifest.path("layers");
      if (!layers.isArray())
        throw new IllegalArgumentException("OCI image manifest has no layer list");
      for (JsonNode layer : layers)
        copyBlob(sourceRepository, targetRepository, layer.path("digest").asText());
    }
    HttpResponse<byte[]> published =
        send(
            HttpRequest.newBuilder(uri(targetRepository, "manifests", digest))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", mediaType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(source.body()))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    requireStatus(published.statusCode(), 201, "write public OCI manifest");
    if (!digest.equals(published.headers().firstValue("Docker-Content-Digest").orElse("")))
      throw new IllegalStateException("Public OCI registry returned a different manifest digest");
  }

  private void copyBlob(String sourceRepository, String targetRepository, String digest)
      throws Exception {
    if (!DIGEST.matcher(digest).matches())
      throw new IllegalArgumentException("OCI image contains an invalid blob digest");
    URI mount =
        URI.create(
            registry.resolve("/v2/" + targetRepository + "/blobs/uploads/").toString()
                + "?mount="
                + encode(digest)
                + "&from="
                + encode(sourceRepository));
    HttpResponse<byte[]> started =
        send(
            HttpRequest.newBuilder(mount)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    if (started.statusCode() == 201) return;
    if (started.statusCode() != 202)
      throw new IllegalStateException(
          "Zot rejected approved blob promotion (HTTP " + started.statusCode() + ")");
    String location =
        started
            .headers()
            .firstValue("Location")
            .orElseThrow(
                () -> new IllegalStateException("Zot did not return an OCI upload location"));
    URI upload = registry.resolve(location);
    if (!sameOrigin(registry, upload) || !upload.getPath().startsWith("/v2/"))
      throw new IllegalStateException("Zot returned an unsafe OCI upload location");

    HttpResponse<InputStream> source =
        send(
            HttpRequest.newBuilder(uri(sourceRepository, "blobs", digest))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    requireStatus(source.statusCode(), 200, "read staged OCI blob");
    URI complete = withDigest(upload, digest);
    try (InputStream body = source.body()) {
      HttpResponse<byte[]> uploaded =
          send(
              HttpRequest.newBuilder(complete)
                  .timeout(Duration.ofMinutes(10))
                  .header("Content-Type", "application/octet-stream")
                  .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> body))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      requireStatus(uploaded.statusCode(), 201, "write public OCI blob");
    }
  }

  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws Exception {
    HttpRequest authenticated =
        authorization.isBlank()
            ? request
            : HttpRequest.newBuilder(request, (name, value) -> true)
                .header("Authorization", authorization)
                .build();
    return http.send(authenticated, handler);
  }

  private URI uri(String repository, String kind, String digest) {
    return registry.resolve("/v2/" + repository + "/" + kind + "/" + digest);
  }

  private static URI withDigest(URI upload, String digest) {
    String separator = upload.getRawQuery() == null ? "?" : "&";
    return URI.create(upload + separator + "digest=" + encode(digest));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String mediaType(HttpResponse<?> response) {
    return response
        .headers()
        .firstValue("Content-Type")
        .orElseThrow(() -> new IllegalStateException("Staged OCI manifest has no content type"))
        .split(";", 2)[0];
  }

  private static void verifyDigest(String expected, byte[] content) throws Exception {
    String actual =
        "sha256:"
            + java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    if (!expected.equals(actual))
      throw new IllegalStateException("OCI manifest bytes failed digest validation");
  }

  private static boolean sameOrigin(URI left, URI right) {
    return left.getScheme().equalsIgnoreCase(right.getScheme())
        && left.getHost().equalsIgnoreCase(right.getHost())
        && effectivePort(left) == effectivePort(right)
        && right.getUserInfo() == null;
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
  }

  private static void requireStatus(int actual, int expected, String operation) {
    if (actual != expected)
      throw new IllegalStateException(operation + " failed (HTTP " + actual + ")");
  }
}
