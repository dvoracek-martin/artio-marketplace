package io.artio.marketplace.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.artio.marketplace")
@org.springframework.scheduling.annotation.EnableScheduling
public class MarketplaceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MarketplaceApplication.class, args);
  }
}
