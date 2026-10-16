package io.artio.marketplace.server;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns abandoned, durably reserved publications to a retryable state after their lease expires.
 */
@Component
public class PublicationReservationRecovery {
  private final JdbcTemplate jdbc;

  public PublicationReservationRecovery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Scheduled(fixedDelayString = "${marketplace.publisher.recovery-interval-ms:60000}")
  @Transactional
  public void recoverExpiredReservations() {
    int recovered =
        jdbc.update(
            "UPDATE marketplace_releases SET publication_state='VALIDATED',publishing_started_at=NULL,row_version=row_version+1 WHERE publication_state='PUBLISHING' AND publishing_started_at < now() - interval '15 minutes'");
    if (recovered > 0) {
      jdbc.update(
          "INSERT INTO marketplace_audit(actor,action,target,outcome,details) VALUES ('system','PUBLISH_RESERVATION_RECOVERY','expired leases','OK',jsonb_build_object('count',?))",
          recovered);
    }
  }
}
