package io.artio.marketplace.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
  private final java.util.Set<String> trustedProxies;
  private final Clock clock;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong requests =
      new java.util.concurrent.atomic.AtomicLong();

  @Autowired
  ApiRateLimitFilter(
      ObjectMapper mapper,
      @Value("${marketplace.rate-limit.public-per-minute:120}") int publicRequestsPerMinute,
      @Value("${marketplace.rate-limit.publisher-per-minute:30}") int publisherRequestsPerMinute,
      @Value("${marketplace.rate-limit.trusted-proxies:}") String trustedProxies) {
    this(
        mapper,
        publicRequestsPerMinute,
        publisherRequestsPerMinute,
        trustedProxies,
        Clock.systemUTC());
  }

  ApiRateLimitFilter(
      ObjectMapper mapper,
      int publicRequestsPerMinute,
      int publisherRequestsPerMinute,
      String trustedProxies,
      Clock clock) {
    if (publicRequestsPerMinute < 1 || publisherRequestsPerMinute < 1)
      throw new IllegalArgumentException("rate limits must be positive");
    this.mapper = mapper;
    this.publicRequestsPerMinute = publicRequestsPerMinute;
    this.publisherRequestsPerMinute = publisherRequestsPerMinute;
    this.trustedProxies =
        java.util.Arrays.stream(trustedProxies.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
    if (windows.size() >= MAX_CLIENT_BUCKETS && !windows.containsKey(key)) {
      if ((requests.incrementAndGet() & 255) == 0)
        windows.entrySet().removeIf(entry -> entry.getValue().expired(now));
      if (windows.size() >= MAX_CLIENT_BUCKETS) {
        reject(response, 60);
        return;
      }
    }

    Window window =
        windows.compute(
            key,
            (ignored, current) -> {
              if (current == null || current.expired(now)) return new Window(now);
              current.record(now);
              return current;
            });
    int limit = publisher ? publisherRequestsPerMinute : publicRequestsPerMinute;
    if (!window.allowed(limit)) {
      reject(response, window.retryAfterSeconds(now));
      return;
    }

    if ((requests.incrementAndGet() & 255) == 0)
      windows.entrySet().removeIf(entry -> entry.getValue().expired(now));
    chain.doFilter(request, response);
  }

  private String clientAddress(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    if (trustedProxies.contains(remote)) {
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null) {
        String first = forwarded.split(",", 2)[0].trim();
        if (first.matches("[0-9a-fA-F:.]{2,45}")) return first.toLowerCase(java.util.Locale.ROOT);
      }
    }
    return remote == null || remote.isBlank() ? "unknown" : remote;
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
