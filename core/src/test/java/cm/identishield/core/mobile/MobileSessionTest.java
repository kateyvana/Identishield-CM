package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.PinStore.AccountPin;
import cm.identishield.core.mobile.SessionTokens.IssuedSession;
import cm.identishield.core.time.VirtualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Flux B côté cœur (carte B4) : le défi est le nonce du PDP (ou un leurre de même forme) ; le PIN
 * est vérifié avant le PDP et compté par origine ; le verdict du PDP est arbitré ; un jeton n'est
 * émis que sur VALID.
 *
 * <p>@covers ES-22
 *
 * <p>@covers ES-26
 */
class MobileSessionTest {

  private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
  private static final String MSISDN = "677000123";
  private static final String PIN = "4096";
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000123");
  private static final String DEVICE = "6f0a2c1e-0000-4000-8000-000000000001";
  private static final String NONCE = "9f86d081884c7d659a2feaa0c55ad015";
  private static final String ORIGIN = "100.64.0.9";

  private final PinHasher hasher = new PinHasher(new PinProperties(1_000, 16, 256));
  private final PinStore pins = mock(PinStore.class);
  private final DeviceClient device = mock(DeviceClient.class);
  private final SessionTokens sessions = mock(SessionTokens.class);
  private final PinAttempts attempts =
      new PinAttempts(new VirtualClock(NOW), 2, Duration.ofMinutes(15));
  private final MobileSession session = new MobileSession(pins, hasher, attempts, device, sessions);

  private void accountWithPin() {
    when(pins.find(MSISDN)).thenReturn(Optional.of(new AccountPin(ACCOUNT, hasher.protect(PIN))));
  }

  private void pdpSays(String verdict) {
    when(device.verify(ACCOUNT.toString(), DEVICE, NONCE, "c2ln"))
        .thenReturn(new Reply(200, Map.of("verdict", verdict)));
  }

  @Test
  void theChallenge_isThePdpNonce_orADecoyOfTheSameShape_forAnUnknownDevice() {
    when(device.nonce(DEVICE)).thenReturn(new Reply(200, Map.of("nonce", NONCE)));
    assertEquals(Map.of("challenge", NONCE), session.challenge(DEVICE).body());

    when(device.nonce("00000000-0000-4000-8000-00000000dead"))
        .thenReturn(new Reply(404, Map.of("code", "DEVICE_UNKNOWN")));
    Answer decoy = session.challenge("00000000-0000-4000-8000-00000000dead");
    assertEquals(200, decoy.status());
    assertTrue(
        decoy.body().get("challenge").toString().matches("^[0-9a-f]{32}$"),
        "même forme qu'un nonce : l'appelant n'apprend pas si l'appareil existe");

    when(device.nonce(DEVICE)).thenReturn(Reply.unreachable());
    assertEquals(503, session.challenge(DEVICE).status());
  }

  @Test
  void wrongPin_isTheUniformAnswer_countsForTheOrigin_andThePdpIsNeverAsked() {
    accountWithPin();
    Answer wrong = session.open(MSISDN, "0000", DEVICE, NONCE, "c2ln", ORIGIN);
    when(pins.find("677000999")).thenReturn(Optional.empty());
    Answer unknown = session.open("677000999", PIN, DEVICE, NONCE, "c2ln", ORIGIN);

    assertEquals(new Answer(401, Map.of("reason", "CREDENTIALS_INVALID"), false, 0), wrong);
    assertEquals(wrong, unknown);
    verifyNoInteractions(device, sessions);
    // Deuxième échec de cette origine : bloquée, avant tout calcul ; le PIN juste n'y change rien.
    Answer locked = session.open(MSISDN, PIN, DEVICE, NONCE, "c2ln", ORIGIN);
    assertEquals(429, locked.status());
    assertEquals("THROTTLED", locked.body().get("reason"));
    assertEquals(15 * 60, locked.throttledSeconds());
    verifyNoInteractions(device, sessions);
    // Une autre origine, même compte : libre.
    pdpSays("VALID");
    when(sessions.issue(ACCOUNT, DEVICE)).thenReturn(new IssuedSession("tok", 300));
    assertEquals(200, session.open(MSISDN, PIN, DEVICE, NONCE, "c2ln", "100.64.0.10").status());
  }

  @Test
  void valid_issuesTheToken_revoked_isKeyRevoked_invalid_isUniform_unreachable_is503() {
    accountWithPin();
    pdpSays("VALID");
    when(sessions.issue(ACCOUNT, DEVICE)).thenReturn(new IssuedSession("tok", 300));
    assertEquals(
        Map.of("sessionToken", "tok", "expiresInSeconds", 300L),
        session.open(MSISDN, PIN, DEVICE, NONCE, "c2ln", ORIGIN).body());

    pdpSays("REVOKED");
    assertEquals(
        Map.of("reason", "KEY_REVOKED"),
        session.open(MSISDN, PIN, DEVICE, NONCE, "c2ln", ORIGIN).body());

    pdpSays("INVALID");
    Answer invalid = session.open(MSISDN, PIN, DEVICE, NONCE, "c2ln", ORIGIN);
    assertEquals(401, invalid.status());
    assertEquals("CREDENTIALS_INVALID", invalid.body().get("reason"));

    when(device.verify(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Reply.unreachable());
    Answer down = session.open(MSISDN, PIN, DEVICE, NONCE, "c2ln", ORIGIN);
    assertEquals(503, down.status());
    assertTrue(down.retryAfter());
    verify(sessions).issue(any(), any()); // une seule émission : le cas VALID
    verify(sessions, never()).touch(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }
}
