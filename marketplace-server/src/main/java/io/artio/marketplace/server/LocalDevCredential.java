package io.artio.marketplace.server;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In-memory capability shared only by the dev UI adapter and existing publisher workflow. */
@Component
@Profile("marketplace-local-dev")
public final class LocalDevCredential {
  private final String bearer;

  public LocalDevCredential() {
    byte[] token = new byte[32];
    new SecureRandom().nextBytes(token);
    bearer = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  public String authorization() {
    return "Bearer " + bearer;
  }
}
