package io.artio.marketplace.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.marketplace.application.ArtifactVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HEAD-verifies all images against one operator-configured Zot endpoint (never an arbitrary URL).
 */
@Component
public final class ZotArtifactVerifier implements ArtifactVerifier {
  private static final Pattern REF =
      Pattern.compile("([a-zA-Z0-9.-]+(?::[0-9]{1,5})?)/([a-zA-Z0-9._/-]+)@sha256:([a-f0-9]{64})");
  private static final String ACCEPT =
      String.join(
          ", ",
          "application/vnd.oci.image.manifest.v1+json",
          "application/vnd.oci.image.index.v1+json",
          "application/vnd.docker.distribution.manifest.v2+json",
          "application/vnd.docker.distribution.manifest.list.v2+json");
  private final String expectedHost;
  private final URI zotBase;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper mapper = new ObjectMapper();

  public ZotArtifactVerifier(
      @Value("${marketplace.registry.public-host:localhost:5000}") String expectedHost,
      @Value("${marketplace.registry.internal-url:http://zot:5000}") URI zotBase) {
    if (zotBase.getUserInfo() != null
        || zotBase.getHost() == null
        || !Set.of("http", "https").contains(zotBase.getScheme()))
      throw new IllegalArgumentException("Invalid configured Zot endpoint");
    this.expectedHost = expectedHost.toLowerCase(java.util.Locale.ROOT);
    this.zotBase = zotBase;
  }

  @Override
  public List<String> verify(String registryHost, String manifestJson) {
    List<String> errors = new ArrayList<>();
    try {
      JsonNode root = mapper.readTree(manifestJson);
      check(root.path("image").asText(null), registryHost, "image", errors);
      JsonNode services = root.path("services");
      if (services.isArray())
        for (int i = 0; i < services.size(); i++)
          check(
              services.get(i).path("image").asText(null),
              registryHost,
              "services[" + i + "].image",
              errors);
    } catch (Exception e) {
      errors.add("manifest could not be inspected");
    }
    return List.copyOf(errors);
  }

  private void check(String reference, String requestedHost, String field, List<String> errors) {
    var match = REF.matcher(reference == null ? "" : reference);
    if (!match.matches()) {
      errors.add(field + " must use a lowercase sha256 digest reference");
      return;
    }
    String host = match.group(1).toLowerCase(java.util.Locale.ROOT),
        repo = match.group(2),
        digest = "sha256:" + match.group(3);
    if (repo.startsWith("/")
        || repo.endsWith("/")
        || repo.contains("//")
        || java.util.Arrays.stream(repo.split("/", -1))
            .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
      errors.add(field + " OCI repository path is invalid");
      return;
    }
    if (!host.equals(expectedHost)
        || !host.equals(requestedHost.toLowerCase(java.util.Locale.ROOT))) {
      errors.add(field + " registry is not the configured public registry");
      return;
    }
    try {
      verifyManifest(repo, digest, field, errors, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      errors.add(field + " registry check was interrupted");
    } catch (Exception e) {
      errors.add(field + " registry could not be reached");
    }
  }

  private void verifyManifest(
      String repository, String digest, String field, List<String> errors, int depth)
      throws Exception {
    if (depth > 2) {
      errors.add(field + " OCI index nesting exceeds the supported limit");
      return;
    }
    URI uri = zotBase.resolve("/v2/" + repository + "/manifests/" + digest);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(8))
            .header("Accept", ACCEPT)
            .GET()
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      errors.add(field + " OCI digest is unavailable (HTTP " + response.statusCode() + ")");
      return;
    }
    byte[] content = response.body();
    String actual =
        "sha256:"
            + java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    String registry = response.headers().firstValue("Docker-Content-Digest").orElse("");
    if (!digest.equals(actual) || !digest.equals(registry)) {
      errors.add(field + " registry manifest bytes do not match the requested digest");
      return;
    }
    JsonNode manifest = mapper.readTree(content);
    JsonNode children = manifest.path("manifests");
    if (children.isArray()) {
      if (children.isEmpty()) {
        errors.add(field + " OCI index has no platform manifests");
        return;
      }
      for (JsonNode child : children) {
        String childDigest = child.path("digest").asText("");
        JsonNode platform = child.path("platform");
        if (!childDigest.matches("sha256:[a-f0-9]{64}")
            || platform.path("os").asText().isBlank()
            || platform.path("architecture").asText().isBlank()) {
          errors.add(field + " OCI index contains an invalid platform descriptor");
          continue;
        }
        verifyManifest(repository, childDigest, field, errors, depth + 1);
      }
    } else if (!manifest.path("config").isObject() || !manifest.path("layers").isArray()) {
      errors.add(field + " is not a supported OCI or Docker image manifest");
    }
  }
}
