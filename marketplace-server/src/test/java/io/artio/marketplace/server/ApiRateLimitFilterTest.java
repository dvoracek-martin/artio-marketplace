package io.artio.marketplace.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiRateLimitFilterTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void limitsPublicAndPublisherTrafficIndependentlyAndReturnsRetryMetadata() throws Exception {
    var filter = new ApiRateLimitFilter(new ObjectMapper(), 2, 1, "", clock);
    var passed = new AtomicInteger();

    for (int i = 0; i < 2; i++)
      assertThat(request(filter, "/api/v1/marketplace/plugins", null, passed).getStatus())
          .isEqualTo(200);
    var limited = request(filter, "/api/v1/marketplace/plugins", null, passed);
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
    assertThat(new ObjectMapper().readTree(limited.getContentAsString()).path("code").asText())
        .isEqualTo("RATE_LIMITED");

    assertThat(request(filter, "/api/v1/admin/marketplace/releases", null, passed).getStatus())
        .isEqualTo(200);
    assertThat(request(filter, "/api/v1/admin/marketplace/releases", null, passed).getStatus())
        .isEqualTo(429);
  }

  @Test
  void forwardedAddressIsUsedOnlyWhenImmediatePeerIsExplicitlyTrusted() throws Exception {
    var trusted = new ApiRateLimitFilter(new ObjectMapper(), 1, 1, "127.0.0.0/8,10.0.0.0/8", clock);
    var ignored = new ApiRateLimitFilter(new ObjectMapper(), 1, 1, "", clock);
    var passed = new AtomicInteger();

    assertThat(request(trusted, "/api/v1/marketplace/plugins", "192.0.2.1", passed).getStatus())
        .isEqualTo(200);
    assertThat(request(trusted, "/api/v1/marketplace/plugins", "192.0.2.2", passed).getStatus())
        .isEqualTo(200);
    assertThat(request(ignored, "/api/v1/marketplace/plugins", "192.0.2.1", passed).getStatus())
        .isEqualTo(200);
    assertThat(request(ignored, "/api/v1/marketplace/plugins", "192.0.2.2", passed).getStatus())
        .isEqualTo(429);
  }

  @Test
  void trustedForwardingWalksFromTheRightAcrossTrustedProxyHops() throws Exception {
    var filter = new ApiRateLimitFilter(new ObjectMapper(), 1, 1, "127.0.0.0/8,10.0.0.0/8", clock);
    var passed = new AtomicInteger();
    var first = request(filter, "/api/v1/marketplace/plugins", "192.0.2.77, 10.0.0.4", passed);
    var sameClient = request(filter, "/api/v1/marketplace/plugins", "192.0.2.77, 10.0.0.9", passed);
    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(sameClient.getStatus()).isEqualTo(429);
  }

  @Test
  void attackerControlledLeftmostForwardedValueCannotChangeTheResolvedClient() throws Exception {
    var filter = new ApiRateLimitFilter(new ObjectMapper(), 1, 1, "127.0.0.0/8", clock);
    var passed = new AtomicInteger();
    var spoofed =
        request(filter, "/api/v1/marketplace/plugins", "203.0.113.9, 198.51.100.7", passed);
    var sameSocketClient =
        request(filter, "/api/v1/marketplace/plugins", "192.0.2.88, 198.51.100.7", passed);
    assertThat(spoofed.getStatus()).isEqualTo(200);
    assertThat(sameSocketClient.getStatus()).isEqualTo(429);
  }

  @Test
  void globalAdmissionCeilingStillBoundsTrafficWhenClientKeysVary() throws Exception {
    var filter = new ApiRateLimitFilter(new ObjectMapper(), 100, 100, 2, 2, "", clock);
    var passed = new AtomicInteger();
    assertThat(requestFrom(filter, "192.0.2.1", passed).getStatus()).isEqualTo(200);
    assertThat(requestFrom(filter, "192.0.2.2", passed).getStatus()).isEqualTo(200);
    assertThat(requestFrom(filter, "192.0.2.3", passed).getStatus()).isEqualTo(429);
  }

  @Test
  void fullBucketTableEvictsLeastRecentlyUsedAndAdmitsANewClient() throws Exception {
    var filter = new ApiRateLimitFilter(new ObjectMapper(), 1, 1, 25_000, 25_000, "", clock);
    var passed = new AtomicInteger();
    for (int i = 0; i < 20_000; i++) {
      String remote = "198.18." + (i / 256) + "." + (i % 256);
      assertThat(requestFrom(filter, remote, passed).getStatus()).isEqualTo(200);
    }
    assertThat(requestFrom(filter, "203.0.113.250", passed).getStatus()).isEqualTo(200);
  }

  @Test
  void operationalRoutesAreNotChargedAgainstMarketplaceApiLimits() throws Exception {
    var filter = new ApiRateLimitFilter(new ObjectMapper(), 1, 1, "", clock);
    var passed = new AtomicInteger();

    for (int i = 0; i < 5; i++)
      assertThat(request(filter, "/actuator/health/readiness", null, passed).getStatus())
          .isEqualTo(200);
    assertThat(passed).hasValue(5);
  }

  private static MockHttpServletResponse request(
      ApiRateLimitFilter filter, String path, String forwardedFor, AtomicInteger passed)
      throws Exception {
    var request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    request.setRemoteAddr("127.0.0.1");
    if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
    return response;
  }

  private static MockHttpServletResponse requestFrom(
      ApiRateLimitFilter filter, String remote, AtomicInteger passed) throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/v1/marketplace/plugins");
    request.setRequestURI("/api/v1/marketplace/plugins");
    request.setRemoteAddr(remote);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
    return response;
  }
}
