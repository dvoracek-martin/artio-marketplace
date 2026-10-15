package io.artio.marketplace.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("zot")
public final class ZotHealthIndicator implements HealthIndicator {
  private final URI base;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public ZotHealthIndicator(
      @Value("${marketplace.registry.internal-url:http://zot:5000}") URI base) {
    this.base = base;
  }

  @Override
  public Health health() {
    try {
      var r =
          client.send(
              HttpRequest.newBuilder(base.resolve("/v2/"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      return r.statusCode() == 200
          ? Health.up().build()
          : Health.down().withDetail("status", r.statusCode()).build();
    } catch (Exception e) {
      return Health.down().withDetail("registry", "unavailable").build();
    }
  }
}
