package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.core.config.RuleSet;
import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.Enrolments.Enrolment;
import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.PinStore.AccountPin;
import cm.identishield.core.mobile.SessionTokens.IssuedSession;
import cm.identishield.core.otp.OtpEmission;
import cm.identishield.core.otp.OtpOutcome;
import cm.identishield.core.otp.OtpService;
import cm.identishield.core.otp.SmsDeliveryException;
import cm.identishield.core.otp.SmsGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Premier enrôlement côté cœur (carte B3a) : aiguillage par le FAIT de liaison énoncé par le PDP,
 * réponse uniforme avant tout appel au PDP, PDP injoignable sans repli sur l'OTP, enrôlement gardé
 * sur panne et consommé après confirmation, 410 identique quelle qu'en soit la raison, relecture du
 * fait sur réponse perdue.
 *
 * <p>@covers ES-22
 *
 * <p>@covers ES-25
 *
 * <p>@covers ES-26
 */
class MobileEnrolmentTest {

  private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
  private static final String MSISDN = "677000200";
  private static final String PIN = "4096";
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000200");
  private static final UUID ENROLMENT = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
  private static final String DEVICE = "6f0a2c1e-0000-4000-8000-000000000001";

  private final PinHasher hasher = new PinHasher(new PinProperties(1_000, 16, 256));
  private final PinStore pins = mock(PinStore.class);
  private final DeviceClient device = mock(DeviceClient.class);
  private final Enrolments enrolments = mock(Enrolments.class);
  private final OtpService otp = mock(OtpService.class);
  private final SmsGateway sms = mock(SmsGateway.class);
  private final SessionTokens sessions = mock(SessionTokens.class);
  private final MobileSession session = mock(MobileSession.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final RuleSet ruleSet =
      new RuleSet("rs-test", Map.of("OTP_ATTEMPTS", 3, "ENROL_TTL", "PT10M"));
  private static final String ORIGIN = "100.64.0.9";
  private final PinAttempts attempts =
      new PinAttempts(Clock.fixed(NOW, ZoneOffset.UTC), 2, java.time.Duration.ofMinutes(15));

  private final MobileEnrolment service =
      new MobileEnrolment(
          pins,
          hasher,
          attempts,
          device,
          enrolments,
          otp,
          sms,
          sessions,
          session,
          ruleSet,
          Clock.fixed(NOW, ZoneOffset.UTC),
          tx);

  MobileEnrolmentTest() {
    when(tx.execute(any()))
        .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
  }

  private void accountWithPin() {
    when(pins.find(MSISDN)).thenReturn(Optional.of(new AccountPin(ACCOUNT, hasher.protect(PIN))));
    when(enrolments.openRecovery(eq(ACCOUNT), any(), any())).thenReturn(ENROLMENT);
  }

  private static Reply reply(int status, Map<String, Object> body) {
    return new Reply(status, body);
  }

  private void enrolmentAt(String stage, Instant expiresAt) {
    when(enrolments.find(ENROLMENT))
        .thenReturn(
            Optional.of(
                new Enrolment(
                    ENROLMENT, ACCOUNT, MSISDN, stage, 0, "Y2hhbGxlbmdl", DEVICE, expiresAt)));
  }

  // ---- start ----------------------------------------------------------------------------------

  @Test
  void unknownAccount_andWrongPin_getTheSameAnswer_andThePdpIsNeverAsked() {
    when(pins.find("677000201")).thenReturn(Optional.empty());
    Answer unknown = service.start("677000201", PIN, ORIGIN, null, null, null);
    accountWithPin();
    Answer wrongPin = service.start(MSISDN, "4097", ORIGIN, null, null, null);

    assertEquals(new Answer(401, Map.of("reason", "CREDENTIALS_INVALID"), false, 0), unknown);
    assertEquals(unknown, wrongPin);
    verifyNoInteractions(device, otp, enrolments);
  }

  @Test
  void pdpUnreachable_isA503WithRetryAfter_andNeverFallsBackToOtp() {
    accountWithPin();
    when(device.state(ACCOUNT.toString())).thenReturn(Reply.unreachable());

    Answer a = service.start(MSISDN, PIN, ORIGIN, null, null, null);

    assertEquals(503, a.status());
    assertEquals("SERVICE_UNAVAILABLE", a.body().get("reason"));
    assertTrue(a.retryAfter());
    verifyNoInteractions(otp, sms, enrolments);
  }

  @Test
  void activeKey_routesToRecovery_withAnEnrolment_andWithoutAnyOtp() {
    accountWithPin();
    when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", "ACTIVE")));

    assertEquals(
        Map.of("next", "RECOVERY", "enrolmentId", ENROLMENT.toString()),
        service.start(MSISDN, PIN, ORIGIN, null, null, null).body());
    // Flux D (B3b) : l'enrôlement s'ouvre à RECOVERY, jamais un OTP (EF-46).
    verify(enrolments).openRecovery(ACCOUNT, NOW, NOW.plus(ruleSet.enrolTtl()));
    verify(enrolments, never()).open(any(), any(), any());
    verifyNoInteractions(otp, sms);
  }

  // ---- recover (flux D, carte B3b) -----------------------------------------------------------

  @Test
  void recover_verifiesNothing_issuesTheAttestationChallenge_andMovesToBind() {
    enrolmentAt("RECOVERY", NOW.plusSeconds(60));
    when(enrolments.toBind(eq(ENROLMENT), anyString(), eq("RECOVERY"))).thenReturn(true);

    Answer a = service.recover(ENROLMENT);

    assertEquals("BIND", a.body().get("next"));
    assertTrue(
        java.util.Base64.getDecoder().decode((String) a.body().get("attestationChallenge")).length
            >= 16);
    verifyNoInteractions(device, otp, sms);
  }

  @Test
  void recover_atAnotherStage_is400_expiredOrConsumed_is410() {
    enrolmentAt("OTP", NOW.plusSeconds(60));
    assertEquals(400, service.recover(ENROLMENT).status());
    enrolmentAt("RECOVERY", NOW);
    assertEquals(410, service.recover(ENROLMENT).status());
    enrolmentAt("DONE", NOW.plusSeconds(60));
    assertEquals(410, service.recover(ENROLMENT).status());
    verify(enrolments, never()).toBind(any(), anyString(), anyString());
  }

  @Test
  void complete_withTheOldCode_passesItToThePdp_andMapsItsVerdicts() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(
            anyString(),
            anyString(),
            any(),
            anyString(),
            anyString(),
            eq("OLD123"),
            eq(ORIGIN),
            any()))
        .thenReturn(reply(401, Map.of("code", "RECOVERY_INVALID")));
    Answer invalid =
        service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln", "OLD123", ORIGIN, null);
    assertEquals(new Answer(401, Map.of("reason", "RECOVERY_INVALID"), false, 0), invalid);
    // L'enrôlement reste en BIND : l'usager retape son code.
    verify(enrolments, never()).consume(any(), anyString());

    when(device.register(
            anyString(),
            anyString(),
            any(),
            anyString(),
            anyString(),
            eq("OLD123"),
            eq(ORIGIN),
            any()))
        .thenReturn(reply(429, Map.of("code", "THROTTLED", "retryAfterSeconds", 90)));
    Answer throttled =
        service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln", "OLD123", ORIGIN, null);
    assertEquals(429, throttled.status());
    assertEquals(90, throttled.throttledSeconds());
    assertEquals(Map.of("reason", "THROTTLED"), throttled.body());
  }

  @Test
  void noKey_orAPendingKeyOnly_routesToOtp() {
    accountWithPin();
    when(enrolments.open(eq(ACCOUNT), any(), any())).thenReturn(ENROLMENT);
    when(otp.issue(MSISDN)).thenReturn(new OtpEmission(MSISDN, "624010677000200", "123456"));
    for (String fact : List.of("NONE", "PENDING")) {
      when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", fact)));
      Answer a = service.start(MSISDN, PIN, ORIGIN, null, null, null);
      assertEquals(Map.of("next", "OTP", "enrolmentId", ENROLMENT.toString()), a.body(), fact);
    }
    verify(enrolments, org.mockito.Mockito.times(2))
        .open(ACCOUNT, NOW, NOW.plus(ruleSet.enrolTtl()));
  }

  @Test
  void smsNetworkDown_isTransient() {
    accountWithPin();
    when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", "NONE")));
    when(enrolments.open(eq(ACCOUNT), any(), any())).thenReturn(ENROLMENT);
    when(otp.issue(MSISDN)).thenReturn(new OtpEmission(MSISDN, "624010677000200", "123456"));
    org.mockito.Mockito.doThrow(new SmsDeliveryException("down"))
        .when(sms)
        .deliver(anyString(), anyString());

    Answer a = service.start(MSISDN, PIN, ORIGIN, null, null, null);
    assertEquals(503, a.status());
    assertTrue(a.retryAfter());
  }

  // ---- start, carte B4 : origine bloquée (P-11) et branche SESSION ---------------------------

  @Test
  void aLockedOrigin_gets429BeforeAnyWork_whileAnotherOriginIsFree() {
    accountWithPin();
    service.start(MSISDN, "0000", ORIGIN, null, null, null);
    service.start(MSISDN, "0001", ORIGIN, null, null, null);
    Answer locked = service.start(MSISDN, PIN, ORIGIN, null, null, null);
    assertEquals(429, locked.status());
    assertEquals(Map.of("reason", "THROTTLED"), locked.body());
    assertEquals(15 * 60, locked.throttledSeconds());
    verifyNoInteractions(device, otp, enrolments);

    when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", "ACTIVE")));
    assertEquals(
        Map.of("next", "RECOVERY", "enrolmentId", ENROLMENT.toString()),
        service.start(MSISDN, PIN, "100.64.0.10", null, null, null).body());
  }

  @Test
  void activeKey_withAValidSignedChallenge_opensTheSession_otherwiseRecovery() {
    accountWithPin();
    when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", "ACTIVE")));
    String nonce = "9f86d081884c7d659a2feaa0c55ad015";
    when(session.prove(ACCOUNT, DEVICE, nonce, "c2ln"))
        .thenReturn(
            new MobileSession.Outcome(
                MobileSession.Proof.VALID, new SessionTokens.IssuedSession("tok", 300)));
    assertEquals(
        Map.of("next", "SESSION", "sessionToken", "tok", "expiresInSeconds", 300L),
        service.start(MSISDN, PIN, ORIGIN, DEVICE, nonce, "c2ln").body());

    when(session.prove(ACCOUNT, DEVICE, nonce, "c2ln"))
        .thenReturn(new MobileSession.Outcome(MobileSession.Proof.INVALID, null));
    assertEquals(
        Map.of("next", "RECOVERY", "enrolmentId", ENROLMENT.toString()),
        service.start(MSISDN, PIN, ORIGIN, DEVICE, nonce, "c2ln").body());

    when(session.prove(ACCOUNT, DEVICE, nonce, "c2ln"))
        .thenReturn(new MobileSession.Outcome(MobileSession.Proof.REVOKED, null));
    Answer revoked = service.start(MSISDN, PIN, ORIGIN, DEVICE, nonce, "c2ln");
    assertEquals(403, revoked.status());
    assertEquals("KEY_REVOKED", revoked.body().get("reason"));

    when(session.prove(ACCOUNT, DEVICE, nonce, "c2ln"))
        .thenReturn(new MobileSession.Outcome(MobileSession.Proof.UNAVAILABLE, null));
    assertEquals(503, service.start(MSISDN, PIN, ORIGIN, DEVICE, nonce, "c2ln").status());
    // Seule la signature INVALIDE ouvre un enrôlement RECOVERY (B3b) ; jamais d'OTP.
    verify(enrolments).openRecovery(eq(ACCOUNT), any(), any());
    verifyNoInteractions(otp, sms);
  }

  // ---- otp ------------------------------------------------------------------------------------

  @Test
  void acceptedOtp_issuesAnAttestationChallengeOfAtLeast16Bytes() {
    enrolmentAt("OTP", NOW.plusSeconds(60));
    when(otp.verify(MSISDN, "123456")).thenReturn(OtpOutcome.ACCEPTED);
    when(enrolments.toBind(eq(ENROLMENT), anyString())).thenReturn(true);

    Answer a = service.otp(ENROLMENT, "123456");

    assertEquals("BIND", a.body().get("next"));
    byte[] challenge = Base64.getDecoder().decode((String) a.body().get("attestationChallenge"));
    assertTrue(challenge.length >= 16);
  }

  @Test
  void wrongOtp_counts_andAfterOtpAttemptsANewOtpIsRequired() {
    enrolmentAt("OTP", NOW.plusSeconds(60));
    when(otp.verify(MSISDN, "000000")).thenReturn(OtpOutcome.REFUSED_UNKNOWN);
    assertEquals(401, service.otp(ENROLMENT, "000000").status());
    verify(enrolments).otpFailed(ENROLMENT);

    when(enrolments.find(ENROLMENT))
        .thenReturn(
            Optional.of(
                new Enrolment(
                    ENROLMENT, ACCOUNT, MSISDN, "OTP", 3, null, null, NOW.plusSeconds(60))));
    assertEquals(
        new Answer(410, Map.of("reason", "OTP_EXPIRED"), false, 0),
        service.otp(ENROLMENT, "123456"));
  }

  // ---- complete -------------------------------------------------------------------------------

  @Test
  void registered_returnsTheCodeOnce_andMovesToConfirm() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(
            anyString(), anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
        .thenReturn(reply(200, Map.of("deviceId", DEVICE, "recoveryCode", "ABCDEF")));
    when(enrolments.toConfirm(ENROLMENT, DEVICE)).thenReturn(true);

    Answer a = service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln", null, ORIGIN, null);

    assertEquals(200, a.status());
    assertEquals(DEVICE, a.body().get("deviceId"));
    verify(device)
        .register(
            ACCOUNT.toString(),
            "cGs=",
            List.of("Y2VydA=="),
            "Y2hhbGxlbmdl",
            "c2ln",
            null,
            ORIGIN,
            null);
  }

  @Test
  void softwareKey_isDeviceUnsupported() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(
            anyString(), anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
        .thenReturn(reply(422, Map.of("code", "DEVICE_UNSUPPORTED")));

    assertEquals(
        new Answer(422, Map.of("reason", "DEVICE_UNSUPPORTED"), false, 0),
        service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln", null, ORIGIN, null));
  }

  @Test
  void pdpDownDuringComplete_keepsTheEnrolment() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(
            anyString(), anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
        .thenReturn(Reply.unreachable());

    Answer a = service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln", null, ORIGIN, null);

    assertEquals(503, a.status());
    verify(enrolments, never()).consume(any(), anyString());
    verify(enrolments, never()).toConfirm(any(), anyString());
  }

  @Test
  void raceLostToAnotherBinding_is410_identicalToAnExpiredEnrolment() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(
            anyString(), anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
        .thenReturn(reply(401, Map.of("code", "RECOVERY_INVALID")));
    Answer race =
        service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln", null, ORIGIN, null);
    verify(enrolments).consume(ENROLMENT, "BIND");

    enrolmentAt("BIND", NOW);
    Answer expired =
        service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln", null, ORIGIN, null);

    assertEquals(new Answer(410, Map.of("reason", "ENROLMENT_EXPIRED"), false, 0), race);
    assertEquals(race, expired);
  }

  // ---- confirm --------------------------------------------------------------------------------

  @Test
  void rightCode_activates_consumesTheEnrolment_andOpensTheSession() {
    enrolmentAt("CONFIRM", NOW.plusSeconds(60));
    when(device.activate(ACCOUNT.toString(), DEVICE, "ABCDEF"))
        .thenReturn(reply(200, Map.of("deviceId", DEVICE)));
    when(enrolments.consume(ENROLMENT, "CONFIRM")).thenReturn(true);
    when(sessions.issue(ACCOUNT, DEVICE)).thenReturn(new IssuedSession("jeton", 300));

    Answer a = service.confirm(ENROLMENT, "ABCDEF");

    assertEquals(Map.of("sessionToken", "jeton", "expiresInSeconds", 300L), a.body());
  }

  @Test
  void wrongCode_isRecoveryInvalid_andTheEnrolmentStaysInConfirm() {
    enrolmentAt("CONFIRM", NOW.plusSeconds(60));
    when(device.activate(ACCOUNT.toString(), DEVICE, "ABCDEG"))
        .thenReturn(reply(401, Map.of("code", "RECOVERY_INVALID")));

    assertEquals(
        new Answer(401, Map.of("reason", "RECOVERY_INVALID"), false, 0),
        service.confirm(ENROLMENT, "ABCDEG"));
    verify(enrolments, never()).consume(any(), anyString());
    verifyNoInteractions(sessions);
  }

  @Test
  void lostActivationReply_whileThePdpSucceeded_doesNotLockTheUserOut() {
    enrolmentAt("CONFIRM", NOW.plusSeconds(60));
    when(device.activate(ACCOUNT.toString(), DEVICE, "ABCDEF")).thenReturn(Reply.unreachable());
    when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", "ACTIVE")));
    when(enrolments.consume(ENROLMENT, "CONFIRM")).thenReturn(true);
    when(sessions.issue(ACCOUNT, DEVICE)).thenReturn(new IssuedSession("jeton", 300));

    Answer a = service.confirm(ENROLMENT, "ABCDEF");

    assertEquals(200, a.status(), "l'activation a réussi au PDP : l'usager n'est pas verrouillé");
    assertEquals("jeton", a.body().get("sessionToken"));
  }

  @Test
  void lostActivationReply_whileTheKeyIsStillPending_isA503_andTheEnrolmentIsKept() {
    enrolmentAt("CONFIRM", NOW.plusSeconds(60));
    when(device.activate(ACCOUNT.toString(), DEVICE, "ABCDEF")).thenReturn(Reply.unreachable());
    when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", "PENDING")));

    Answer a = service.confirm(ENROLMENT, "ABCDEF");

    assertEquals(503, a.status());
    assertTrue(a.retryAfter());
    verify(enrolments, never()).consume(any(), anyString());
    verifyNoInteractions(sessions);
  }

  @Test
  void mtlsRefusal_isNeverReportedAsAWrongCode() {
    enrolmentAt("CONFIRM", NOW.plusSeconds(60));
    when(device.activate(ACCOUNT.toString(), DEVICE, "ABCDEF"))
        .thenReturn(reply(401, Map.of("code", "MTLS_REQUIRED")));
    when(device.state(ACCOUNT.toString())).thenReturn(Reply.unreachable());

    assertEquals(503, service.confirm(ENROLMENT, "ABCDEF").status());
  }

  @Test
  void aConsumedEnrolment_answers410ToAnyReplay_likeAnExpiredOne() {
    enrolmentAt("DONE", NOW.plusSeconds(60));
    Answer replay = service.confirm(ENROLMENT, "ABCDEF");
    enrolmentAt("CONFIRM", NOW.minusSeconds(1));
    Answer expired = service.confirm(ENROLMENT, "ABCDEF");

    assertEquals(new Answer(410, Map.of("reason", "ENROLMENT_EXPIRED"), false, 0), replay);
    assertEquals(replay, expired);
    verifyNoInteractions(device, sessions);
  }

  @Test
  void twoConcurrentConfirmations_openOneSessionOnly() {
    enrolmentAt("CONFIRM", NOW.plusSeconds(60));
    when(device.activate(ACCOUNT.toString(), DEVICE, "ABCDEF"))
        .thenReturn(reply(200, Map.of("deviceId", DEVICE)));
    when(enrolments.consume(ENROLMENT, "CONFIRM")).thenReturn(false);

    assertEquals(410, service.confirm(ENROLMENT, "ABCDEF").status());
    verifyNoInteractions(sessions);
  }

  @Test
  void unknownEnrolment_orWrongStage_isRequestInvalid() {
    when(enrolments.find(ENROLMENT)).thenReturn(Optional.empty());
    assertEquals(400, service.otp(ENROLMENT, "123456").status());
    enrolmentAt("BIND", NOW.plusSeconds(60));
    assertEquals(400, service.otp(ENROLMENT, "123456").status());
    assertFalse(service.otp(ENROLMENT, "123456").retryAfter());
  }
}
