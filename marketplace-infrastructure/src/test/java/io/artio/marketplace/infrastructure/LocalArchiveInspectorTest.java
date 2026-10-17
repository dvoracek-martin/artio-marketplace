package io.artio.marketplace.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArchiveInspectorTest {
  @TempDir Path temp;
  private final LocalArchiveInspector inspector = new LocalArchiveInspector(new ObjectMapper());

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
