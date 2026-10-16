package io.artio.marketplace.server;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only public endpoint; the signed payload is authoritative only after Core verification. */
@RestController
@RequestMapping("/api/v1/marketplace/security")
public final class PublicAdvisoryController {
  private final SignedAdvisoryFeedService feed;

  public PublicAdvisoryController(SignedAdvisoryFeedService feed) {
    this.feed = feed;
  }

  @GetMapping("/advisories")
  public ResponseEntity<Map<String, Object>> advisories() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache().mustRevalidate())
        .header(HttpHeaders.VARY, "Accept-Encoding")
        .body(feed.signedSnapshot());
  }
}
