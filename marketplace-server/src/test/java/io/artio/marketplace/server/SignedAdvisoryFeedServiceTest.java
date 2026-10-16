package io.artio.marketplace.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class SignedAdvisoryFeedServiceTest {
  @Test
  void signsCanonicalSnapshotWithTheDedicatedEd25519Key() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair keyPair = generator.generateKeyPair();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ResultSet resultSet = mock(ResultSet.class);
    org.mockito.Mockito.when(resultSet.next()).thenReturn(true);
    org.mockito.Mockito.when(resultSet.getLong(1)).thenReturn(17L);
    org.mockito.Mockito.when(resultSet.getObject(2, OffsetDateTime.class))
        .thenReturn(OffsetDateTime.parse("2026-10-16T10:00:00Z"));
    doAnswer(
            invocation ->
                ((ResultSetExtractor<?>) invocation.getArgument(1)).extractData(resultSet))
        .when(jdbc)
        .query(anyString(), any(ResultSetExtractor.class));
    org.mockito.Mockito.when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenReturn(java.util.List.of());

    SignedAdvisoryFeedService service =
        new SignedAdvisoryFeedService(
            jdbc,
            new ObjectMapper(),
            "marketplace-advisory-2026",
            Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
            java.time.Duration.ofHours(24));

    Map<String, Object> envelope = service.signedSnapshot();
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
    @SuppressWarnings("unchecked")
    Map<String, String> signatureBlock = (Map<String, String>) envelope.get("signature");
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(keyPair.getPublic());
    verifier.update(
        ReleaseDescriptorCanonicalizer.canonicalBytes(
            new ObjectMapper().writeValueAsString(payload)));

    assertThat(payload).containsEntry("schemaVersion", 1).containsEntry("sequence", 17L);
    assertThat(payload).containsEntry("keyId", "marketplace-advisory-2026");
    assertThat(signatureBlock).containsEntry("algorithm", "Ed25519");
    assertThat(signatureBlock).containsEntry("keyId", "marketplace-advisory-2026");
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(signatureBlock.get("value"))))
        .isTrue();
  }

  @Test
  void refusesToPublishWhenDedicatedSigningKeyIsNotConfigured() {
    SignedAdvisoryFeedService service =
        new SignedAdvisoryFeedService(
            mock(JdbcTemplate.class), new ObjectMapper(), "", "", java.time.Duration.ofHours(24));

    assertThatThrownBy(service::signedSnapshot)
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Marketplace advisory signing is not configured");
  }

  @Test
  void advisorySigningCanRotateToANewKeyWithoutAcceptingTheRetiredKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair oldKey = generator.generateKeyPair();
    KeyPair newKey = generator.generateKeyPair();
    JdbcTemplate jdbc = mockFeedState();

    Map<String, Object> oldEnvelope = signedSnapshot(jdbc, "advisory-old", oldKey);
    Map<String, Object> newEnvelope = signedSnapshot(jdbc, "advisory-new", newKey);

    assertThat(verifySnapshot(oldEnvelope, oldKey)).isTrue();
    assertThat(verifySnapshot(newEnvelope, newKey)).isTrue();
    assertThat(verifySnapshot(newEnvelope, oldKey)).isFalse();
    @SuppressWarnings("unchecked")
    Map<String, String> oldSignature = (Map<String, String>) oldEnvelope.get("signature");
    @SuppressWarnings("unchecked")
    Map<String, String> newSignature = (Map<String, String>) newEnvelope.get("signature");
    assertThat(oldSignature).containsEntry("keyId", "advisory-old");
    assertThat(newSignature).containsEntry("keyId", "advisory-new");
  }

  private Map<String, Object> signedSnapshot(JdbcTemplate jdbc, String keyId, KeyPair keyPair) {
    return new SignedAdvisoryFeedService(
            jdbc,
            new ObjectMapper(),
            keyId,
            Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
            java.time.Duration.ofHours(24))
        .signedSnapshot();
  }

  private JdbcTemplate mockFeedState() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ResultSet resultSet = mock(ResultSet.class);
    org.mockito.Mockito.when(resultSet.next()).thenReturn(true);
    org.mockito.Mockito.when(resultSet.getLong(1)).thenReturn(17L);
    org.mockito.Mockito.when(resultSet.getObject(2, OffsetDateTime.class))
        .thenReturn(OffsetDateTime.parse("2026-10-16T10:00:00Z"));
    doAnswer(
            invocation ->
                ((ResultSetExtractor<?>) invocation.getArgument(1)).extractData(resultSet))
        .when(jdbc)
        .query(anyString(), any(ResultSetExtractor.class));
    org.mockito.Mockito.when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenReturn(java.util.List.of());
    return jdbc;
  }

  private boolean verifySnapshot(Map<String, Object> envelope, KeyPair keyPair) throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
    @SuppressWarnings("unchecked")
    Map<String, String> signatureBlock = (Map<String, String>) envelope.get("signature");
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(keyPair.getPublic());
    verifier.update(
        ReleaseDescriptorCanonicalizer.canonicalBytes(
            new ObjectMapper().writeValueAsString(payload)));
    return verifier.verify(Base64.getUrlDecoder().decode(signatureBlock.get("value")));
  }
}
