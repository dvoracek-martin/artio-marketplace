package io.artio.marketplace.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;

/** Inspects Docker-save and OCI-layout archives without extracting or executing image content. */
@Component
public final class LocalArchiveInspector {
  private static final int MAX_ENTRIES = 100_000;
  private static final int MAX_METADATA = 16 * 1024 * 1024;
  private static final long MAX_EXPANDED = 16L * 1024 * 1024 * 1024;
  private final ObjectMapper mapper;

  public LocalArchiveInspector(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Inventory inspect(Path archive) throws IOException {
    if (!Files.isRegularFile(archive)
        || Files.size(archive) < 512
        || Files.size(archive) > 4L * 1024 * 1024 * 1024)
      throw new IOException("Archive must be a regular tar file no larger than 4 GiB");
    try (InputStream in = Files.newInputStream(archive)) {
      byte[] header = in.readNBytes(4);
      if (header.length >= 2 && header[0] == 0x1f && header[1] == (byte) 0x8b)
        throw new IOException(
            "Compressed archives are not accepted; upload docker save or OCI archive output");
    }
    Set<String> names = new HashSet<>();
    Map<String, byte[]> metadata = new HashMap<>();
    List<String> imageFiles = new ArrayList<>();
    long expanded = 0;
    int count = 0;
    try (TarArchiveInputStream tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (++count > MAX_ENTRIES) throw new IOException("Archive contains too many entries");
        String name = entry.getName();
        validateName(name);
        if (!names.add(name)) throw new IOException("Archive contains a duplicate path: " + name);
        if (entry.isSymbolicLink()
            || entry.isLink()
            || entry.isCharacterDevice()
            || entry.isBlockDevice()
            || entry.isFIFO()
            || (!entry.isDirectory() && !entry.isFile()))
          throw new IOException("Archive contains a link or special file: " + name);
        if (entry.getSize() < 0 || (expanded += entry.getSize()) > MAX_EXPANDED)
          throw new IOException("Archive expanded-size limit exceeded");
        if (entry.isFile()
            && (name.equals("manifest.json")
                || name.equals("index.json")
                || name.equals("oci-layout")
                || name.endsWith(".json"))) {
          if (entry.getSize() > MAX_METADATA)
            throw new IOException("Image metadata file is too large");
          metadata.put(name, readBounded(tar, (int) entry.getSize()));
        } else if (entry.isFile() && name.startsWith("blobs/sha256/")) {
          String expected = name.substring("blobs/sha256/".length());
          if (!expected.matches("[a-f0-9]{64}"))
            throw new IOException("OCI blob path has invalid digest: " + name);
          String actual = sha256(tar);
          if (!expected.equals(actual)) throw new IOException("OCI blob digest mismatch: " + name);
        } else {
          drain(tar);
        }
        if (entry.isFile() && (name.equals("manifest.json") || name.equals("index.json")))
          imageFiles.add(name);
      }
    }
    if (metadata.containsKey("manifest.json")) return inspectDocker(archive, metadata, names);
    if (metadata.containsKey("index.json") && metadata.containsKey("oci-layout"))
      return inspectOci(archive, metadata, names);
    throw new IOException(
        "Archive is neither a Docker save archive nor a valid OCI layout archive");
  }

  private Inventory inspectDocker(Path archive, Map<String, byte[]> metadata, Set<String> names)
      throws IOException {
    JsonNode root = mapper.readTree(metadata.get("manifest.json"));
    if (!root.isArray() || root.isEmpty())
      throw new IOException("Docker save manifest.json is empty or malformed");
    List<Image> images = new ArrayList<>();
    for (JsonNode image : root) {
      String config = image.path("Config").asText();
      JsonNode tags = image.path("RepoTags");
      JsonNode layers = image.path("Layers");
      if (!safeMember(config)
          || !names.contains(config)
          || !tags.isArray()
          || tags.isEmpty()
          || !layers.isArray()
          || layers.isEmpty())
        throw new IOException("Docker save manifest has missing config, tags, or layers");
      for (JsonNode layer : layers)
        if (!safeMember(layer.asText()) || !names.contains(layer.asText()))
          throw new IOException("Docker save references a missing layer");
      byte[] configBytes = metadata.get(config);
      if (configBytes == null) configBytes = readDockerConfig(archive, config);
      JsonNode configJson = mapper.readTree(configBytes);
      String os = configJson.path("os").asText("unknown");
      String architecture = configJson.path("architecture").asText("unknown");
      for (JsonNode tag : tags) {
        String value = tag.asText();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}") || value.contains(".."))
          throw new IOException("Docker archive contains an unsafe repository tag");
        images.add(new Image(value, os, architecture));
      }
    }
    return new Inventory("DOCKER_SAVE", images, names.size());
  }

  /** Docker Desktop may store the Config member as an OCI content-addressed blob. */
  private static byte[] readDockerConfig(Path archive, String config) throws IOException {
    if (!config.matches("blobs/sha256/[a-f0-9]{64}"))
      throw new IOException(
          "Docker save config metadata is missing or exceeds the configured size limit");
    try (TarArchiveInputStream tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!config.equals(entry.getName())) continue;
        if (!entry.isFile() || entry.getSize() > MAX_METADATA)
          throw new IOException("Docker save config metadata is invalid or too large");
        return readBounded(tar, (int) entry.getSize());
      }
    }
    throw new IOException("Docker save config metadata is missing");
  }

  private Inventory inspectOci(Path archive, Map<String, byte[]> metadata, Set<String> names)
      throws IOException {
    JsonNode layout = mapper.readTree(metadata.get("oci-layout"));
    JsonNode root = mapper.readTree(metadata.get("index.json"));
    if (!"1.0.0".equals(layout.path("imageLayoutVersion").asText())
        || root.path("schemaVersion").asInt() != 2
        || !root.path("manifests").isArray()
        || root.path("manifests").isEmpty())
      throw new IOException("OCI layout/index metadata is invalid");
    List<Image> images = new ArrayList<>();
    Set<String> visitedIndexes = new HashSet<>();
    for (JsonNode descriptor : root.path("manifests")) {
      String tag =
          descriptor.path("annotations").path("org.opencontainers.image.ref.name").asText();
      if (tag.isBlank()) tag = "imported-" + images.size();
      appendOciDescriptor(archive, descriptor, tag, names, visitedIndexes, images, 0);
    }
    return new Inventory("OCI_LAYOUT", images, names.size());
  }

  private void appendOciDescriptor(
      Path archive,
      JsonNode descriptor,
      String inheritedTag,
      Set<String> names,
      Set<String> visitedIndexes,
      List<Image> images,
      int depth)
      throws IOException {
    if (depth > 8) throw new IOException("OCI index nesting is too deep");
    String digest = descriptor.path("digest").asText();
    if (!digest.matches("sha256:[a-f0-9]{64}"))
      throw new IOException("OCI index references an invalid manifest digest");
    String blob = "blobs/sha256/" + digest.substring("sha256:".length());
    if (!names.contains(blob))
      throw new IOException("OCI index references a missing manifest blob");
    JsonNode annotations = descriptor.path("annotations");
    String tag = annotations.path("org.opencontainers.image.ref.name").asText(inheritedTag);
    if (tag.isBlank()) tag = inheritedTag;
    if (!tag.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}") || tag.contains(".."))
      throw new IOException("OCI archive contains an unsafe repository tag");

    JsonNode document = mapper.readTree(readBlob(archive, blob));
    JsonNode children = document.path("manifests");
    if (children.isArray()) {
      if (document.path("schemaVersion").asInt() != 2 || children.isEmpty())
        throw new IOException("Nested OCI index is invalid or contains no manifests");
      if (!visitedIndexes.add(digest))
        throw new IOException("OCI index contains a reference cycle");
      for (JsonNode child : children)
        appendOciDescriptor(archive, child, tag, names, visitedIndexes, images, depth + 1);
      visitedIndexes.remove(digest);
      return;
    }

    JsonNode config = document.path("config");
    JsonNode layers = document.path("layers");
    if (document.path("schemaVersion").asInt() != 2
        || !validOciBlob(config, names)
        || !layers.isArray()) throw new IOException("OCI image manifest is malformed");
    for (JsonNode layer : layers)
      if (!validOciBlob(layer, names))
        throw new IOException("OCI image manifest has a missing layer");

    JsonNode platform = descriptor.path("platform");
    images.add(
        new Image(
            tag,
            platform.path("os").asText("unknown"),
            platform.path("architecture").asText("unknown")));
  }

  private byte[] readBlob(Path archive, String blobName) throws IOException {
    try (TarArchiveInputStream tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!blobName.equals(entry.getName())) continue;
        if (!entry.isFile() || entry.getSize() < 0 || entry.getSize() > MAX_METADATA)
          throw new IOException("OCI index metadata blob is invalid or too large");
        return readBounded(tar, (int) entry.getSize());
      }
    }
    throw new IOException("OCI index references a missing manifest blob");
  }

  private boolean validOciBlob(JsonNode descriptor, Set<String> names) {
    String digest = descriptor.path("digest").asText();
    return digest.matches("sha256:[a-f0-9]{64}")
        && names.contains("blobs/sha256/" + digest.substring("sha256:".length()));
  }

  private static void validateName(String name) throws IOException {
    if (name == null
        || name.isBlank()
        || name.length() > 512
        || name.startsWith("/")
        || name.startsWith("\\")
        || name.contains("\\")
        || name.indexOf('\0') >= 0) throw new IOException("Archive contains an unsafe path");
    for (String part : name.split("/"))
      if (part.equals("..") || part.equals("."))
        throw new IOException("Archive path traversal detected");
  }

  private static boolean safeMember(String name) {
    return name != null
        && !name.startsWith("/")
        && !name.contains("\\")
        && !name.contains("..")
        && !name.isBlank();
  }

  private static byte[] readBounded(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 64 * 1024));
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (out.size() + n > max) throw new IOException("Metadata exceeds configured maximum");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private static void drain(InputStream in) throws IOException {
    byte[] b = new byte[65536];
    while (in.read(b) != -1) {}
  }

  private static String sha256(InputStream in) throws IOException {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) d.update(b, 0, n);
      return HexFormat.of().formatHex(d.digest());
    } catch (Exception e) {
      throw new IOException("SHA-256 unavailable", e);
    }
  }

  private static String sha256(TarArchiveInputStream in) throws IOException {
    return sha256((InputStream) in);
  }

  public record Image(String archiveTag, String os, String architecture) {}

  public record Inventory(String format, List<Image> images, int entryCount) {
    public Inventory {
      images = List.copyOf(images);
    }
  }
}
