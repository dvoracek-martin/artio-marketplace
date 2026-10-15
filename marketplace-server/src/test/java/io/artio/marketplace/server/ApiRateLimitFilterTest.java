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
    var trusted = new ApiRateLimitFilter(new ObjectMapper(), 1, 1, "127.0.0.1", clock);
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
}
