package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cm.identishield.core.mobile.SessionTokens.IssuedSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Session minimale (carte B3a, décision 4 ; réponse du tech lead, point 2) : jeton SecureRandom
 * d'au moins 256 bits, jamais tronqué ; SEULE son empreinte SHA-256, sans sel, est conservée ;
 * échéance fixe APP_SESSION_TTL.
 */
class SessionTokensTest {

  private static final Instant T0 = Instant.parse("2026-10-21T08:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000123");

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SessionTokens tokens =
      new SessionTokens(
          jdbc,
          Clock.fixed(T0, ZoneOffset.UTC),
          TTL,
          Duration.ofSeconds(60),
          Duration.ofMinutes(30));

  @Test
  void token_hasAtLeast256Bits_andOnlyItsSha256IsStored() throws Exception {
    IssuedSession s = tokens.issue(ACCOUNT, "dev-1");

    assertTrue(Base64.getUrlDecoder().decode(s.token()).length >= 32, "au moins 256 bits");
    ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);
    verify(jdbc)
        .update(
            eq(SessionTokens.INSERT_SQL),
            stored.capture(),
            eq(ACCOUNT),
            eq("dev-1"),
            any(),
            any(),
            any(),
            any());
    String expected =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(s.token().getBytes(StandardCharsets.US_ASCII)));
    assertEquals(expected, stored.getValue());
    assertFalse(stored.getValue().toString().contains(s.token()));
  }

  @Test
  void expiry_isTheNamedParameter_andTwoTokensDiffer() {
    IssuedSession a = tokens.issue(ACCOUNT, "dev-1");
    IssuedSession b = tokens.issue(ACCOUNT, "dev-1");
    assertEquals(TTL.toSeconds(), a.expiresInSeconds());
    assertNotEquals(a.token(), b.token());
  }
}
