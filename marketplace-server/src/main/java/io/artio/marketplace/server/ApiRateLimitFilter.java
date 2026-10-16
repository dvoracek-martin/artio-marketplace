package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A bounded per-client fixed-window limit for the public catalog and trusted publisher API. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class ApiRateLimitFilter extends OncePerRequestFilter {
  private static final long WINDOW_MILLIS = 60_000;
  private static final int MAX_CLIENT_BUCKETS = 20_000;

  private final ObjectMapper mapper;
  private final int publicRequestsPerMinute;
  private final int publisherRequestsPerMinute;
  private final int publicGlobalRequestsPerMinute;
  private final int publisherGlobalRequestsPerMinute;
  private final java.util.List<Cidr> trustedProxies;
  private final Clock clock;
  private final Map<String, Window> windows = new LinkedHashMap<>(256, .75f, true);
  private final Object windowsLock = new Object();
  private final Window publicGlobal = new Window(0);
  private final Window publisherGlobal = new Window(0);
  private final java.util.concurrent.atomic.AtomicLong requests =
      new java.util.concurrent.atomic.AtomicLong();

  @Autowired
  ApiRateLimitFilter(
      ObjectMapper mapper,
      @Value("${marketplace.rate-limit.public-per-minute:120}") int publicRequestsPerMinute,
      @Value("${marketplace.rate-limit.publisher-per-minute:30}") int publisherRequestsPerMinute,
      @Value("${marketplace.rate-limit.public-global-per-minute:200000}")
          int publicGlobalRequestsPerMinute,
      @Value("${marketplace.rate-limit.publisher-global-per-minute:5000}")
          int publisherGlobalRequestsPerMinute,
      @Value("${marketplace.rate-limit.trusted-proxies:}") String trustedProxies) {
    this(
        mapper,
        publicRequestsPerMinute,
        publisherRequestsPerMinute,
        publicGlobalRequestsPerMinute,
        publisherGlobalRequestsPerMinute,
        trustedProxies,
        Clock.systemUTC());
  }

  ApiRateLimitFilter(
      ObjectMapper mapper,
      int publicRequestsPerMinute,
      int publisherRequestsPerMinute,
      String trustedProxies,
      Clock clock) {
    this(
        mapper,
        publicRequestsPerMinute,
        publisherRequestsPerMinute,
        Math.max(200_000, publicRequestsPerMinute),
        Math.max(5_000, publisherRequestsPerMinute),
        trustedProxies,
        clock);
  }

  ApiRateLimitFilter(
      ObjectMapper mapper,
      int publicRequestsPerMinute,
      int publisherRequestsPerMinute,
      int publicGlobalRequestsPerMinute,
      int publisherGlobalRequestsPerMinute,
      String trustedProxies,
      Clock clock) {
    if (publicRequestsPerMinute < 1
        || publisherRequestsPerMinute < 1
        || publicGlobalRequestsPerMinute < 1
        || publisherGlobalRequestsPerMinute < 1)
      throw new IllegalArgumentException("rate limits must be positive");
    this.mapper = mapper;
    this.publicRequestsPerMinute = publicRequestsPerMinute;
    this.publisherRequestsPerMinute = publisherRequestsPerMinute;
    this.publicGlobalRequestsPerMinute = publicGlobalRequestsPerMinute;
    this.publisherGlobalRequestsPerMinute = publisherGlobalRequestsPerMinute;
    this.trustedProxies =
        java.util.Arrays.stream(trustedProxies.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(Cidr::parse)
            .toList();
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    boolean publisher = path.startsWith("/api/v1/admin/marketplace/");
    if (!publisher && !path.startsWith("/api/v1/marketplace/")) {
      chain.doFilter(request, response);
      return;
    }

    long now = clock.millis();
    String client = clientAddress(request);
    String key = (publisher ? "publisher:" : "public:") + client;
    int limit = publisher ? publisherRequestsPerMinute : publicRequestsPerMinute;
    Window global = publisher ? publisherGlobal : publicGlobal;
    global.record(now);
    int globalLimit = publisher ? publisherGlobalRequestsPerMinute : publicGlobalRequestsPerMinute;
    if (!global.allowed(globalLimit)) {
      reject(response, global.retryAfterSeconds(now));
      return;
    }
    Window window = window(key, now);
    if (!window.allowed(limit)) {
      reject(response, window.retryAfterSeconds(now));
      return;
    }

    if ((requests.incrementAndGet() & 255) == 0) cleanup(now);
    chain.doFilter(request, response);
  }

  private String clientAddress(HttpServletRequest request) {
    String remote = normalize(request.getRemoteAddr());
    if (remote == null) return "unknown";
    if (!trusted(remote)) return remote;
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.length() > 2048) return remote;
    String[] chain = forwarded.split(",", -1);
    String current = remote;
    for (int index = chain.length - 1; index >= 0; index--) {
      if (!trusted(current)) break;
      String candidate = normalize(chain[index].trim());
      if (candidate == null) return remote;
      current = candidate;
    }
    return current;
  }

  private boolean trusted(String address) {
    return trustedProxies.stream().anyMatch(proxy -> proxy.contains(address));
  }

  private Window window(String key, long now) {
    synchronized (windowsLock) {
      Window current = windows.get(key);
      if (current == null || current.expired(now)) {
        if (current == null && windows.size() >= MAX_CLIENT_BUCKETS) {
          cleanup(now);
          if (windows.size() >= MAX_CLIENT_BUCKETS) {
            var oldest = windows.keySet().iterator();
            if (oldest.hasNext()) {
              oldest.next();
              oldest.remove();
            }
          }
        }
        current = new Window(now);
        windows.put(key, current);
      } else {
        current.record(now);
      }
      return current;
    }
  }

  private void cleanup(long now) {
    synchronized (windowsLock) {
      windows.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }
  }

  private static String normalize(String address) {
    if (address == null || address.isBlank() || !address.matches("[0-9a-fA-F:.]{2,45}"))
      return null;
    try {
      return InetAddress.getByName(address).getHostAddress().toLowerCase(java.util.Locale.ROOT);
    } catch (Exception invalid) {
      return null;
    }
  }

  private record Cidr(byte[] network, int prefix) {
    static Cidr parse(String input) {
      String[] parts = input.split("/", -1);
      String address = normalize(parts[0]);
      if (address == null || parts.length > 2)
        throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + input);
      try {
        byte[] bytes = InetAddress.getByName(address).getAddress();
        int bits = parts.length == 1 ? bytes.length * 8 : Integer.parseInt(parts[1]);
        if (bits < 0 || bits > bytes.length * 8)
          throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + input);
        byte[] masked = bytes.clone();
        for (int i = 0; i < masked.length; i++) {
          int keep = Math.max(0, Math.min(8, bits - i * 8));
          masked[i] = (byte) (Byte.toUnsignedInt(masked[i]) & (0xff << (8 - keep)));
        }
        return new Cidr(masked, bits);
      } catch (Exception invalid) {
        throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + input, invalid);
      }
    }

    boolean contains(String address) {
      try {
        byte[] candidate = InetAddress.getByName(address).getAddress();
        if (candidate.length != network.length) return false;
        int whole = prefix / 8, partial = prefix % 8;
        for (int i = 0; i < whole; i++) if (candidate[i] != network[i]) return false;
        return partial == 0
            || (candidate[whole] & (0xff << (8 - partial)))
                == (network[whole] & (0xff << (8 - partial)));
      } catch (Exception invalid) {
        return false;
      }
    }
  }

  private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getOutputStream(),
        java.util.Map.of(
            "code", "RATE_LIMITED",
            "message", "Too many requests; retry after the indicated interval.",
            "requestId", UUID.randomUUID().toString()));
  }

  private static final class Window {
    private long startedAt;
    private int count;

    private Window(long startedAt) {
      this.startedAt = startedAt;
      this.count = 1;
    }

    private synchronized void record(long now) {
      if (now - startedAt >= WINDOW_MILLIS) {
        startedAt = now;
        count = 1;
      } else {
        count++;
      }
    }

    private synchronized boolean allowed(int limit) {
      return count <= limit;
    }

    private synchronized boolean expired(long now) {
      return now - startedAt >= WINDOW_MILLIS;
    }

    private synchronized long retryAfterSeconds(long now) {
      return Math.max(1, (WINDOW_MILLIS - (now - startedAt) + 999) / 1000);
    }
  }
}
