package io.artio.marketplace.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArchiveInspectorTest {
  @TempDir Path temp;
  private final ObjectMapper mapper = new ObjectMapper();
  private final LocalArchiveInspector inspector = new LocalArchiveInspector(mapper);

  @Test
  void acceptsDockerSaveAndReturnsPlatformInventory() throws Exception {
    Path archive =
        tar(
            entry(
                "manifest.json",
                "[{\"Config\":\"sha.json\",\"RepoTags\":[\"example/plugin:1.0\"],\"Layers\":[\"layer/layer.tar\"]}]"),
            entry("sha.json", "{\"os\":\"linux\",\"architecture\":\"amd64\"}"),
            entry("layer/layer.tar", "layer bytes"));
    var result = inspector.inspect(archive);
    assertEquals("DOCKER_SAVE", result.format());
    assertEquals("linux", result.images().getFirst().os());
    assertEquals("amd64", result.images().getFirst().architecture());
  }

  @Test
  void acceptsDockerDesktopSaveWithContentAddressedConfigBlob() throws Exception {
    byte[] config =
        "{\"os\":\"linux\",\"architecture\":\"arm64\"}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String digest =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(config));
    Path archive =
        tar(
            entry("blobs/sha256/" + digest, config),
            entry(
                "manifest.json",
                "[{\"Config\":\"blobs/sha256/"
                    + digest
                    + "\",\"RepoTags\":[\"example/plugin:1.0\"],\"Layers\":[\"layer/layer.tar\"]}]"),
            entry("layer/layer.tar", "layer bytes"));
    var result = inspector.inspect(archive);
    assertEquals("DOCKER_SAVE", result.format());
    assertEquals("arm64", result.images().getFirst().architecture());
  }

  @Test
  void rejectsPathTraversalAndLinks() throws Exception {
    Path traversal = tar(entry("../escape", "bad"));
    assertThrows(IOException.class, () -> inspector.inspect(traversal));
    Path linked = tar(link("manifest.json", "../../outside"));
    assertThrows(IOException.class, () -> inspector.inspect(linked));
  }

  @Test
  void rejectsCompressedArchive() throws Exception {
    Path gzip = temp.resolve("compressed.tar.gz");
    Files.write(gzip, new byte[] {0x1f, (byte) 0x8b, 0, 0, 0, 0, 0, 0});
    assertThrows(IOException.class, () -> inspector.inspect(gzip));
  }

  @Test
  void rejectsArchiveOverFourGiBBeforeReadingItsContents() throws Exception {
    Path oversized = temp.resolve("oversized.tar");
    try (RandomAccessFile sparse = new RandomAccessFile(oversized.toFile(), "rw")) {
      sparse.setLength(4L * 1024 * 1024 * 1024 + 1);
    }
    assertThrows(IOException.class, () -> inspector.inspect(oversized));
  }

  @Test
  void acceptsOciLayoutWithAmd64AndArm64Index() throws Exception {
    byte[] configAmd64 = "{\"os\":\"linux\",\"architecture\":\"amd64\"}".getBytes();
    byte[] configArm64 = "{\"os\":\"linux\",\"architecture\":\"arm64\"}".getBytes();
    byte[] layerAmd64 = "amd64-layer".getBytes();
    byte[] layerArm64 = "arm64-layer".getBytes();
    byte[] manifestAmd64 = ociManifest(configAmd64, layerAmd64);
    byte[] manifestArm64 = ociManifest(configArm64, layerArm64);
    ArrayNode manifests = mapper.createArrayNode();
    manifests.add(indexDescriptor(manifestAmd64, "amd64"));
    manifests.add(indexDescriptor(manifestArm64, "arm64"));
    ObjectNode index = mapper.createObjectNode().put("schemaVersion", 2);
    index.set("manifests", manifests);

    Path archive =
        tar(
            entry("oci-layout", "{\"imageLayoutVersion\":\"1.0.0\"}"),
            entry("index.json", mapper.writeValueAsBytes(index)),
            blob(manifestAmd64),
            blob(manifestArm64),
            blob(configAmd64),
            blob(configArm64),
            blob(layerAmd64),
            blob(layerArm64));

    var inventory = inspector.inspect(archive);
    assertEquals("OCI_LAYOUT", inventory.format());
    assertEquals(
        List.of("amd64", "arm64"),
        inventory.images().stream().map(LocalArchiveInspector.Image::architecture).toList());
    assertEquals(
        List.of("linux", "linux"),
        inventory.images().stream().map(LocalArchiveInspector.Image::os).toList());
  }

  @Test
  void rejectsDuplicatePathsAndTamperedContentAddressedBlobs() throws Exception {
    Path duplicate =
        tar(
            entry("oci-layout", "{\"imageLayoutVersion\":\"1.0.0\"}"),
            entry("oci-layout", "{\"imageLayoutVersion\":\"1.0.0\"}"));
    assertThrows(IOException.class, () -> inspector.inspect(duplicate));

    byte[] expected = "original blob".getBytes();
    Path tampered =
        tar(
            entry("oci-layout", "{\"imageLayoutVersion\":\"1.0.0\"}"),
            entry("index.json", "{}"),
            entry("blobs/sha256/" + sha256(expected), "different blob"));
    assertThrows(IOException.class, () -> inspector.inspect(tampered));
  }

  @Test
  void rejectsDockerSaveWithMissingLayerOrMalformedManifest() throws Exception {
    Path missingLayer =
        tar(
            entry(
                "manifest.json",
                "[{\"Config\":\"sha.json\",\"RepoTags\":[\"example/plugin:1.0\"],\"Layers\":[\"layer/missing.tar\"]}]"),
            entry("sha.json", "{\"os\":\"linux\",\"architecture\":\"amd64\"}"));
    assertThrows(IOException.class, () -> inspector.inspect(missingLayer));

    Path malformed = tar(entry("manifest.json", "not-json"));
    assertThrows(IOException.class, () -> inspector.inspect(malformed));
  }

  private byte[] ociManifest(byte[] config, byte[] layer) throws Exception {
    ObjectNode configDescriptor =
        mapper
            .createObjectNode()
            .put("mediaType", "application/vnd.oci.image.config.v1+json")
            .put("digest", "sha256:" + sha256(config))
            .put("size", config.length);
    ObjectNode layerDescriptor =
        mapper
            .createObjectNode()
            .put("mediaType", "application/vnd.oci.image.layer.v1.tar")
            .put("digest", "sha256:" + sha256(layer))
            .put("size", layer.length);
    ObjectNode manifest = mapper.createObjectNode().put("schemaVersion", 2);
    manifest.set("config", configDescriptor);
    manifest.set("layers", mapper.createArrayNode().add(layerDescriptor));
    return mapper.writeValueAsBytes(manifest);
  }

  private ObjectNode indexDescriptor(byte[] manifest, String architecture) throws Exception {
    ObjectNode platform =
        mapper.createObjectNode().put("os", "linux").put("architecture", architecture);
    return mapper
        .createObjectNode()
        .put("mediaType", "application/vnd.oci.image.manifest.v1+json")
        .put("digest", "sha256:" + sha256(manifest))
        .put("size", manifest.length)
        .set("platform", platform);
  }

  private Entry blob(byte[] bytes) throws Exception {
    return entry("blobs/sha256/" + sha256(bytes), bytes);
  }

  private String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private Path tar(Entry... entries) throws IOException {
    Path file = temp.resolve("fixture-" + System.nanoTime() + ".tar");
    try (var out = new TarArchiveOutputStream(Files.newOutputStream(file))) {
      out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (Entry e : entries) {
        TarArchiveEntry item =
            e.link == null
                ? new TarArchiveEntry(e.name)
                : new TarArchiveEntry(e.name, TarArchiveEntry.LF_SYMLINK);
        if (e.link != null) item.setLinkName(e.link);
        else item.setSize(e.bytes.length);
        out.putArchiveEntry(item);
        if (e.link == null) out.write(e.bytes);
        out.closeArchiveEntry();
      }
    }
    return file;
  }

  private Entry entry(String name, String value) {
    return new Entry(name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
  }

  private Entry entry(String name, byte[] bytes) {
    return new Entry(name, bytes, null);
  }

  private Entry link(String name, String target) {
    return new Entry(name, new byte[0], target);
  }

  private record Entry(String name, byte[] bytes, String link) {}
}
