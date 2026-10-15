package io.artio.marketplace.infrastructure;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Content-addressed, non-overwriting image storage with decode and dimension limits. */
@Component
public final class MediaStore {
  private static final long ICON_LIMIT = 1_048_576, SCREENSHOT_LIMIT = 5_242_880;
  private static final long MAX_PIXELS = 40_000_000;
  private final Path root;
  private final JdbcTemplate jdbc;

  public MediaStore(
      @Value("${marketplace.media.root:/var/lib/artio-marketplace/assets}") String root,
      JdbcTemplate jdbc) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.jdbc = jdbc;
  }

  public Asset store(MultipartFile file, String kind) {
    if (!kind.equals("icon") && !kind.equals("screenshot"))
      throw new IllegalArgumentException("kind must be icon or screenshot");
    long limit = kind.equals("icon") ? ICON_LIMIT : SCREENSHOT_LIMIT;
    if (file.isEmpty() || file.getSize() > limit)
      throw new IllegalArgumentException(kind + " exceeds its upload size limit");
    try {
      byte[] bytes = file.getBytes();
      String type = sniffAndDecode(bytes);
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      var existing =
          jdbc.query(
              "SELECT asset_id,content_type,byte_size,width,height FROM marketplace_media_assets WHERE sha256=?",
              (rs, n) ->
                  new Asset(
                      rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)),
              digest);
      if (!existing.isEmpty()) {
        read(existing.getFirst().assetId());
        return existing.getFirst();
      }
      String id = "sha256-" + digest;
      Path target =
          root.resolve(digest.substring(0, 2)).resolve(digest + "." + extension(type)).normalize();
      if (!target.startsWith(root)) throw new IllegalArgumentException("invalid media target");
      Files.createDirectories(target.getParent());
      try (var out =
          Files.newOutputStream(
              target,
              java.nio.file.StandardOpenOption.CREATE_NEW,
              java.nio.file.StandardOpenOption.WRITE)) {
        out.write(bytes);
      }
      int[] dims = dimensions(bytes);
      String relative = root.relativize(target).toString().replace('\\', '/');
      jdbc.update(
          "INSERT INTO marketplace_media_assets(asset_id,sha256,relative_path,content_type,byte_size,width,height) VALUES (?,?,?,?,?,?,?)",
          id,
          digest,
          relative,
          type,
          bytes.length,
          dims[0],
          dims[1]);
      return new Asset(id, type, bytes.length, dims[0], dims[1]);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Image could not be safely stored");
    }
  }

  public Stored read(String id) {
    var row =
        jdbc
            .query(
                "SELECT relative_path,content_type,sha256 FROM marketplace_media_assets WHERE asset_id=?",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
                id)
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Asset not found"));
    try {
      Path file = root.resolve(row[0]).normalize();
      if (!file.startsWith(root) || Files.isSymbolicLink(file)) throw new IllegalStateException();
      byte[] data = Files.readAllBytes(file);
      if (!HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(data))
          .equals(row[2])) throw new IllegalStateException();
      return new Stored(data, row[1], row[2]);
    } catch (Exception e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Catalog asset is unavailable");
    }
  }

  private static int[] dimensions(byte[] bytes) throws Exception {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) throw new IllegalArgumentException("unsupported image encoding");
      var r = readers.next();
      try {
        r.setInput(in, true, true);
        return new int[] {r.getWidth(0), r.getHeight(0)};
      } finally {
        r.dispose();
      }
    }
  }

  private static String sniffAndDecode(byte[] bytes) throws Exception {
    String type;
    if (bytes.length >= 8
        && bytes[0] == (byte) 0x89
        && bytes[1] == 'P'
        && bytes[2] == 'N'
        && bytes[3] == 'G'
        && bytes[4] == 13
        && bytes[5] == 10
        && bytes[6] == 26
        && bytes[7] == 10) type = "image/png";
    else if (bytes.length >= 3
        && (bytes[0] & 255) == 255
        && (bytes[1] & 255) == 216
        && (bytes[2] & 255) == 255) type = "image/jpeg";
    else if (bytes.length >= 12
        && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
        && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
      type = "image/webp";
    else throw new IllegalArgumentException("only PNG and JPEG image data is accepted");
    int[] dims = dimensions(bytes);
    if (dims[0] < 1 || dims[1] < 1 || (long) dims[0] * dims[1] > MAX_PIXELS)
      throw new IllegalArgumentException("image dimensions exceed the pixel limit");
    try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var r = ImageIO.getImageReaders(in).next();
      try {
        r.setInput(in, true, true);
        if (r.read(0) == null) throw new IllegalArgumentException("invalid image data");
      } finally {
        r.dispose();
      }
    }
    return type;
  }

  private static String extension(String type) {
    return type.equals("image/png") ? "png" : type.equals("image/webp") ? "webp" : "jpg";
  }

  public record Asset(String assetId, String contentType, long size, int width, int height) {}

  public record Stored(byte[] bytes, String contentType, String sha256) {}
}
