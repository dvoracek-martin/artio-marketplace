package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.marketplace.infrastructure.LocalArchiveInspector;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Streaming archive intake for the loopback-only development console. */
@RestController
@Profile("marketplace-local-dev")
@RequestMapping("/api/v1/dev-admin/imports")
public class LocalDevImportController {
  private static final Logger LOGGER = LoggerFactory.getLogger(LocalDevImportController.class);
  private static final long MAX_ARCHIVE = 4L * 1024 * 1024 * 1024;
  private static final Semaphore IMPORT_SLOT = new Semaphore(1);
  private static final long MAX_QUARANTINE_TOTAL = 8L * 1024 * 1024 * 1024;
  private final ExecutorService importExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            var thread = new Thread(r, "marketplace-local-oci-import");
            thread.setDaemon(true);
            return thread;
          });
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final LocalArchiveInspector inspector;
  private final Path root;
  private final String zotUrl;
  private final String registryHost;
  private final String importerPasswordFile;

  public LocalDevImportController(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      LocalArchiveInspector inspector,
      @Value("${MARKETPLACE_LOCAL_QUARANTINE_ROOT:/var/lib/artio-marketplace/quarantine}")
          String root,
      @Value("${MARKETPLACE_ZOT_URL:http://127.0.0.1:5000}") String zotUrl,
      @Value("${MARKETPLACE_REGISTRY_HOST:localhost:5000}") String registryHost,
      @Value("${MARKETPLACE_LOCAL_IMPORTER_PASSWORD_FILE:}") String importerPasswordFile) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.inspector = inspector;
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.zotUrl = zotUrl;
    this.registryHost = registryHost;
    this.importerPasswordFile = importerPasswordFile;
  }

  @PostMapping
  @Transactional
  public Map<String, Object> create(
      @RequestBody CreateImport request, @RequestHeader("Idempotency-Key") String key) {
    if (request == null
        || request.pluginId() == null
        || !request.pluginId().matches("[a-z0-9][a-z0-9._-]{0,127}")
        || request.version() == null
        || !request
            .version()
            .matches(
                "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?([+][0-9A-Za-z.-]+)?")
        || request.archiveTag() == null
        || !request.archiveTag().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}")
        || request.role() == null
        || !(request.role().equals("MAIN")
            || request.role().matches("SIDECAR:[a-z0-9][a-z0-9._-]{0,63}"))
        || key == null
        || !key.matches("[A-Za-z0-9._:-]{8,160}"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Plugin, version, archive tag, role, and a valid Idempotency-Key are required");
    var old =
        jdbc.query(
            "SELECT job_id::text,state FROM marketplace_dev_import_jobs WHERE idempotency_key=?",
            (rs, n) -> Map.of("id", rs.getString(1), "state", rs.getString(2)),
            key);
    if (!old.isEmpty())
      return Map.of(
          "jobId",
          old.getFirst().get("id"),
          "state",
          old.getFirst().get("state"),
          "idempotent",
          true);
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO marketplace_dev_import_jobs(job_id,source,state,plugin_id,plugin_version,requested_role,source_image_tag,idempotency_key) VALUES (?::uuid,'LOCAL_DEV_UI','CREATED',?,?,?,?,?)",
        id,
        request.pluginId(),
        request.version(),
        request.role(),
        request.archiveTag(),
        key);
    audit("CREATE_IMPORT", id);
    return Map.of(
        "jobId",
        id,
        "state",
        "CREATED",
        "uploadPath",
        "/api/v1/dev-admin/imports/" + id + "/archive");
  }

  @PutMapping("/{jobId}/archive")
  @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> upload(
      @PathVariable UUID jobId,
      @RequestHeader(value = "X-Archive-Name", defaultValue = "plugin.tar") String filename,
      HttpServletRequest request) {
    if (filename.length() > 255
        || filename.contains("/")
        || filename.contains("\\")
        || filename.contains(".."))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Archive name must be a plain filename");
    if (!IMPORT_SLOT.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "An archive import is already active");
    Path dir = root.resolve(jobId.toString()).normalize();
    Path archive = dir.resolve("upload.tar");
    boolean handedOff = false;
    try {
      if (!dir.startsWith(root))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job path");
      var jobs =
          jdbc.query(
              "SELECT plugin_id,plugin_version,requested_role,state FROM marketplace_dev_import_jobs WHERE job_id=?::uuid",
              (rs, n) ->
                  new Job(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
              jobId.toString());
      if (jobs.isEmpty())
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found");
      Job job = jobs.getFirst();
      if (!"CREATED".equals(job.state()))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Import job already received an archive");
      long declared = request.getContentLengthLong();
      if (declared <= 0 || declared > MAX_ARCHIVE)
        throw new ResponseStatusException(
            HttpStatus.PAYLOAD_TOO_LARGE, "Archive must have a known length no greater than 4 GiB");
      if (quarantineBytes() + declared > MAX_QUARANTINE_TOTAL)
        throw new ResponseStatusException(
            HttpStatus.INSUFFICIENT_STORAGE, "Local archive quarantine is full (8 GiB limit)");
      Files.createDirectories(root);
      Files.createDirectory(dir);
      jdbc.update(
          "UPDATE marketplace_dev_import_jobs SET state='RECEIVING',updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid AND state='CREATED'",
          jobId.toString());
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long count = 0;
      byte[] buffer = new byte[1024 * 1024];
      try (InputStream in = request.getInputStream();
          var out =
              Files.newOutputStream(
                  archive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        int n;
        long nextProgress = 8L * 1024 * 1024;
        while ((n = in.read(buffer)) != -1) {
          count += n;
          if (count > MAX_ARCHIVE)
            throw new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE, "Archive exceeds 4 GiB");
          digest.update(buffer, 0, n);
          out.write(buffer, 0, n);
          if (count >= nextProgress) {
            jdbc.update(
                "UPDATE marketplace_dev_import_jobs SET received_bytes=?,updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid AND state='RECEIVING'",
                count,
                jobId.toString());
            nextProgress = count + 8L * 1024 * 1024;
          }
        }
      }
      if (count != declared)
        throw new IOException("Request body length does not match Content-Length");
      String sha = HexFormat.of().formatHex(digest.digest());
      jdbc.update(
          "UPDATE marketplace_dev_import_jobs SET state='UPLOADED',received_bytes=?,archive_filename=?,archive_sha256=?,updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid",
          count,
          filename,
          sha,
          jobId.toString());
      long uploadedBytes = count;
      importExecutor.submit(
          () -> {
            try {
              processImport(jobId, job, archive, uploadedBytes, sha, filename);
            } finally {
              IMPORT_SLOT.release();
              recoverQueuedImports();
            }
          });
      handedOff = true;
      return Map.of(
          "jobId",
          jobId.toString(),
          "state",
          "UPLOADED",
          "receivedBytes",
          count,
          "totalBytes",
          declared,
          "message",
          "Archive stored; inspection and Zot staging are running.");
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception failure) {
      LOGGER.warn("Local OCI import job {} failed: {}", jobId, safeDiagnostic(failure));
      jdbc.update(
          "UPDATE marketplace_dev_import_jobs SET state='FAILED',error_code='IMPORT_FAILED',updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid",
          jobId.toString());
      try {
        if (Files.exists(archive)) Files.delete(archive);
      } catch (IOException ignored) {
      }
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "OCI archive import failed: " + safeDiagnostic(failure), failure);
    } finally {
      if (!handedOff) IMPORT_SLOT.release();
    }
  }

  @org.springframework.web.bind.annotation.GetMapping("/{jobId}")
  public Map<String, Object> status(@PathVariable UUID jobId) {
    var rows =
        jdbc.query(
            "SELECT state,received_bytes,archive_sha256,error_code,updated_at FROM marketplace_dev_import_jobs WHERE job_id=?::uuid",
            (rs, n) ->
                Map.<String, Object>of(
                    "state",
                    rs.getString(1),
                    "receivedBytes",
                    rs.getLong(2),
                    "sha256",
                    rs.getString(3) == null ? "" : rs.getString(3),
                    "error",
                    rs.getString(4) == null ? "" : rs.getString(4),
                    "updatedAt",
                    rs.getTimestamp(5).toInstant().toString()),
            jobId.toString());
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found");
    var result = new java.util.LinkedHashMap<>(rows.getFirst());
    jdbc.query(
        "SELECT public_reference,source_image,platforms,staging_repository,staging_tag,digest,role FROM marketplace_dev_import_artifacts WHERE job_id=?::uuid ORDER BY role",
        (rs) -> {
          String publicReference = rs.getString(1);
          result.put("image", publicReference);
          result.put("publicReference", publicReference);
          result.put("sourceImage", rs.getString(2));
          try {
            result.put("platforms", mapper.readTree(rs.getString(3)));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          result.put(
              "stagingReference", registryHost + "/" + rs.getString(4) + ":" + rs.getString(5));
          result.put("digest", rs.getString(6));
          result.put("role", rs.getString(7));
        },
        jobId.toString());
    return Map.copyOf(result);
  }

  @PostMapping("/{jobId}/retry")
  public Map<String, Object> retry(@PathVariable UUID jobId) {
    if (!IMPORT_SLOT.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "An archive import is already active");
    try {
      var rows =
          jdbc.query(
              "SELECT plugin_id,plugin_version,requested_role,state,archive_filename,archive_sha256 FROM marketplace_dev_import_jobs WHERE job_id=?::uuid",
              (rs, n) ->
                  new RetryJob(
                      new Job(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                      rs.getString(5),
                      rs.getString(6)),
              jobId.toString());
      if (rows.isEmpty())
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found");
      var item = rows.getFirst();
      if (!"FAILED".equals(item.job().state()) || item.filename() == null)
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Only a failed job with a retained archive can be retried");
      Path archive = root.resolve(jobId.toString()).resolve("upload.tar").normalize();
      if (!archive.startsWith(root) || !Files.isRegularFile(archive))
        throw new ResponseStatusException(
            HttpStatus.GONE, "Retained import archive is unavailable");
      String sha = shaFile(archive);
      if (!sha.equals(item.sha()))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Retained import archive digest changed");
      long size = Files.size(archive);
      jdbc.update(
          "DELETE FROM marketplace_dev_import_archives WHERE job_id=?::uuid", jobId.toString());
      jdbc.update(
          "UPDATE marketplace_dev_import_jobs SET state='UPLOADED',error_code=NULL,retry_count=retry_count+1,updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid AND state='FAILED'",
          jobId.toString());
      importExecutor.submit(
          () -> {
            try {
              processImport(jobId, item.job(), archive, size, sha, item.filename());
            } finally {
              IMPORT_SLOT.release();
              recoverQueuedImports();
            }
          });
      return Map.of("jobId", jobId.toString(), "state", "UPLOADED", "retryCount", true);
    } catch (ResponseStatusException e) {
      IMPORT_SLOT.release();
      throw e;
    } catch (Exception e) {
      IMPORT_SLOT.release();
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not retry import job", e);
    }
  }

  private void processImport(
      UUID jobId, Job job, Path archive, long count, String sha, String filename) {
    try {
      jdbc.update(
          "UPDATE marketplace_dev_import_jobs SET state='INSPECTING',updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid",
          jobId.toString());
      LocalArchiveInspector.Inventory inventory = inspector.inspect(archive);
      jdbc.update(
          "UPDATE marketplace_dev_import_jobs SET state='IMPORTING',updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid",
          jobId.toString());
      String sourceTag =
          jdbc.queryForObject(
              "SELECT source_image_tag FROM marketplace_dev_import_jobs WHERE job_id=?::uuid",
              String.class,
              jobId.toString());
      String pathVersion = job.version().replace('+', '_').toLowerCase(java.util.Locale.ROOT);
      String staging =
          "staging/plugins/"
              + job.pluginId()
              + "/"
              + pathVersion
              + "/"
              + job.role().toLowerCase(java.util.Locale.ROOT).replace(':', '-');
      String stagingTag = "import-" + jobId.toString().replace("-", "");
      if (importerPasswordFile.isBlank() || !Files.isRegularFile(Path.of(importerPasswordFile)))
        throw new IOException(
            "Local OCI importer is not configured; enable the dev Compose profile with importer credentials");
      String sourceReference = selectArchiveTag(inventory, sourceTag);
      copyToZot(
          archive,
          inventory.format(),
          sourceReference,
          staging,
          stagingTag,
          Path.of(importerPasswordFile));
      String digestRef = registryDigest(staging, stagingTag, Path.of(importerPasswordFile));
      String publicRef =
          registryHost
              + "/public/plugins/"
              + job.pluginId()
              + "/"
              + pathVersion
              + "/"
              + job.role().toLowerCase(java.util.Locale.ROOT).replace(':', '-')
              + "@"
              + digestRef;
      String inventoryJson = mapper.writeValueAsString(inventory.images());
      String archiveId = UUID.randomUUID().toString();
      jdbc.update(
          "INSERT INTO marketplace_dev_import_archives(archive_id,job_id,original_filename,archive_format,sha256,byte_size,inventory) VALUES (?::uuid,?::uuid,?,?,?,?,?::jsonb)",
          archiveId,
          jobId.toString(),
          filename,
          inventory.format(),
          sha,
          count,
          inventoryJson);
      jdbc.update(
          "INSERT INTO marketplace_dev_import_artifacts(artifact_id,job_id,archive_id,role,source_image,staging_repository,staging_tag,digest,media_type,platforms,public_reference) VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?,'application/vnd.oci.image.index.v1+json',?::jsonb,?)",
          UUID.randomUUID().toString(),
          jobId.toString(),
          archiveId,
          job.role(),
          sourceReference,
          staging,
          stagingTag,
          digestRef,
          inventoryJson,
          publicRef);
      jdbc.update(
          "UPDATE marketplace_dev_import_jobs SET state='STAGED',updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid",
          jobId.toString());
      audit("STAGE_IMPORTED_ARTIFACT", jobId.toString());
    } catch (Exception failure) {
      LOGGER.warn("Local OCI import job {} failed: {}", jobId, safeDiagnostic(failure));
      jdbc.update(
          "UPDATE marketplace_dev_import_jobs SET state='FAILED',error_code='IMPORT_FAILED',updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid",
          jobId.toString());
      try {
        jdbc.update(
            "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('local-dev-ui','IMPORT_FAILED',?,'ERROR',jsonb_build_object('reason',?))",
            jobId.toString(),
            failure.getMessage() == null ? "import failed" : failure.getMessage());
      } catch (Exception ignored) {
      }
    }
  }

  @jakarta.annotation.PostConstruct
  void recoverQueuedImports() {
    if (!IMPORT_SLOT.tryAcquire()) return;
    try {
      var queued =
          jdbc.query(
              "SELECT job_id::text,plugin_id,plugin_version,requested_role,state,archive_filename,archive_sha256 FROM marketplace_dev_import_jobs WHERE state IN ('UPLOADED','INSPECTING','IMPORTING') ORDER BY created_at",
              (rs, n) ->
                  new Recovery(
                      rs.getString(1),
                      new Job(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                      rs.getString(6),
                      rs.getString(7)));
      if (!queued.isEmpty()) {
        var item = queued.getFirst();
        UUID id = UUID.fromString(item.id());
        Path archive = root.resolve(item.id()).resolve("upload.tar").normalize();
        if (!archive.startsWith(root)
            || item.filename() == null
            || !Files.isRegularFile(archive)
            || !shaFile(archive).equals(item.sha())) {
          jdbc.update(
              "UPDATE marketplace_dev_import_jobs SET state='FAILED',error_code='RECOVERY_ARCHIVE_MISSING',updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid",
              item.id());
          IMPORT_SLOT.release();
          return;
        }
        long size = Files.size(archive);
        importExecutor.submit(
            () -> {
              try {
                processImport(id, item.job(), archive, size, item.sha(), item.filename());
              } finally {
                IMPORT_SLOT.release();
                recoverQueuedImports();
              }
            });
        return;
      }
    } catch (Exception failure) {
      IMPORT_SLOT.release();
      throw new IllegalStateException("Could not recover local OCI import queue", failure);
    }
    IMPORT_SLOT.release();
  }

  @jakarta.annotation.PreDestroy
  void stopImportExecutor() {
    importExecutor.shutdownNow();
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelay = 3600000L,
      initialDelay = 3600000L)
  void cleanExpiredQuarantine() {
    var expired =
        jdbc.query(
            "SELECT job_id::text FROM marketplace_dev_import_jobs WHERE expires_at<now() AND state IN ('STAGED','FAILED','CANCELLED','EXPIRED') ORDER BY expires_at LIMIT 100",
            (rs, n) -> rs.getString(1));
    for (String id : expired) {
      try {
        UUID jobId = UUID.fromString(id);
        Path directory = root.resolve(jobId.toString()).normalize();
        Path archive = directory.resolve("upload.tar").normalize();
        if (!directory.startsWith(root) || !archive.startsWith(root)) continue;
        Files.deleteIfExists(archive);
        try (var entries = Files.list(directory)) {
          if (entries.findAny().isEmpty()) Files.deleteIfExists(directory);
        }
        jdbc.update(
            "UPDATE marketplace_dev_import_jobs SET state='EXPIRED',updated_at=now(),row_version=row_version+1 WHERE job_id=?::uuid AND expires_at<now() AND state IN ('STAGED','FAILED','CANCELLED','EXPIRED')",
            id);
      } catch (Exception ignored) {
        /* A later bounded pass retries only this exact expired job. */
      }
    }
  }

  private long quarantineBytes() throws IOException {
    if (!Files.exists(root)) return 0;
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .mapToLong(
              path -> {
                try {
                  return Files.size(path);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              })
          .sum();
    }
  }

  private static String shaFile(Path file) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[1024 * 1024];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String safeDiagnostic(Exception failure) {
    String message =
        failure.getClass().getSimpleName()
            + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    String sanitized =
        message
            .replaceAll("(?i)(basic\\s+)[A-Za-z0-9+/=]+", "$1[REDACTED]")
            .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]")
            .replaceAll("(?i)(password|token|secret)(\\s*[=:]\\s*)[^\\s,;]+", "$1$2[REDACTED]")
            .replaceAll("(://)[^/@\\s]+:[^/@\\s]+@", "$1[REDACTED]@")
            .replaceAll("[\\r\\n\\t]", " ");
    return sanitized.substring(0, Math.min(512, sanitized.length()));
  }

  private String selectArchiveTag(LocalArchiveInspector.Inventory inventory, String requested) {
    for (var image : inventory.images())
      if (requested == null || requested.equals(image.archiveTag())) return image.archiveTag();
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Selected archive image tag is not present in the uploaded archive");
  }

  private void copyToZot(
      Path archive,
      String format,
      String imageTag,
      String repository,
      String tag,
      Path passwordFile)
      throws Exception {
    Path auth = Files.createTempFile(root, "skopeo-auth-", ".json");
    try {
      String password = Files.readString(passwordFile).trim();
      if (password.isBlank()) throw new IOException("Importer credential file is empty");
      String encoded =
          java.util.Base64.getEncoder()
              .encodeToString(
                  ("oci-importer:" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      Files.writeString(auth, "{\"auths\":{\"127.0.0.1:5000\":{\"auth\":\"" + encoded + "\"}}}");
      try {
        Files.setPosixFilePermissions(
            auth, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
      }
      String prefix = "OCI_LAYOUT".equals(format) ? "oci-archive:" : "docker-archive:";
      var command =
          new java.util.ArrayList<>(
              java.util.List.of(
                  "/usr/bin/skopeo",
                  "copy",
                  "--all",
                  "--dest-authfile",
                  auth.toString(),
                  "--dest-tls-verify=false",
                  prefix + archive + ":" + imageTag,
                  "docker://127.0.0.1:5000/" + repository + ":" + tag));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      var output = new java.io.ByteArrayOutputStream();
      Thread drain =
          new Thread(
              () -> {
                try (var in = process.getInputStream()) {
                  byte[] b = new byte[4096];
                  int n;
                  while ((n = in.read(b)) != -1) {
                    synchronized (output) {
                      if (output.size() < 65536)
                        output.write(b, 0, Math.min(n, 65536 - output.size()));
                    }
                  }
                } catch (IOException ignored) {
                }
              },
              "skopeo-output-drain");
      drain.setDaemon(true);
      drain.start();
      boolean done = process.waitFor(20, java.util.concurrent.TimeUnit.MINUTES);
      if (!done) {
        process.destroyForcibly();
        throw new IOException("Skopeo import timed out");
      }
      drain.join(2000);
      if (process.exitValue() != 0)
        throw new IOException(
            "Skopeo rejected archive: " + output.toString(java.nio.charset.StandardCharsets.UTF_8));
    } finally {
      Files.deleteIfExists(auth);
    }
  }

  private String registryDigest(String repository, String tag, Path passwordFile) throws Exception {
    String password = Files.readString(passwordFile).trim();
    String token =
        java.util.Base64.getEncoder()
            .encodeToString(
                ("oci-importer:" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var uri = java.net.URI.create(zotUrl + "/v2/" + repository + "/manifests/" + tag);
    var req =
        java.net.http.HttpRequest.newBuilder(uri)
            .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
            .header("Authorization", "Basic " + token)
            .header(
                "Accept",
                "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json")
            .build();
    var response =
        java.net.http.HttpClient.newHttpClient()
            .send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() != 200)
      throw new IOException("Zot did not return the imported image manifest");
    String digest = response.headers().firstValue("Docker-Content-Digest").orElse("");
    if (!digest.matches("sha256:[a-f0-9]{64}"))
      throw new IOException("Zot returned an invalid content digest");
    return digest;
  }

  private void audit(String action, String target) {
    jdbc.update(
        "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('local-dev-ui',?,?, 'OK','{}'::jsonb)",
        action,
        target);
  }

  private record CreateImport(String pluginId, String version, String role, String archiveTag) {}

  private record Job(String pluginId, String version, String role, String state) {}

  private record RetryJob(Job job, String filename, String sha) {}

  private record Recovery(String id, Job job, String filename, String sha) {}
}
