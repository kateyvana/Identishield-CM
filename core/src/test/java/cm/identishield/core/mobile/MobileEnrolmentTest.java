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
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final RuleSet ruleSet =
      new RuleSet("rs-test", Map.of("OTP_ATTEMPTS", 3, "ENROL_TTL", "PT10M"));

  private final MobileEnrolment service =
      new MobileEnrolment(
          pins,
          hasher,
          device,
          enrolments,
          otp,
          sms,
          sessions,
          ruleSet,
          Clock.fixed(NOW, ZoneOffset.UTC),
          tx);

  MobileEnrolmentTest() {
    when(tx.execute(any()))
        .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
  }

  private void accountWithPin() {
    when(pins.find(MSISDN)).thenReturn(Optional.of(new AccountPin(ACCOUNT, hasher.protect(PIN))));
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
    Answer unknown = service.start("677000201", PIN);
    accountWithPin();
    Answer wrongPin = service.start(MSISDN, "4097");

    assertEquals(new Answer(401, Map.of("reason", "CREDENTIALS_INVALID"), false), unknown);
    assertEquals(unknown, wrongPin);
    verifyNoInteractions(device, otp, enrolments);
  }

  @Test
  void pdpUnreachable_isA503WithRetryAfter_andNeverFallsBackToOtp() {
    accountWithPin();
    when(device.state(ACCOUNT.toString())).thenReturn(Reply.unreachable());

    Answer a = service.start(MSISDN, PIN);

    assertEquals(503, a.status());
    assertEquals("SERVICE_UNAVAILABLE", a.body().get("reason"));
    assertTrue(a.retryAfter());
    verifyNoInteractions(otp, sms, enrolments);
  }

  @Test
  void activeKey_routesToRecovery_withoutAnyOtp() {
    accountWithPin();
    when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", "ACTIVE")));

    assertEquals(Map.of("next", "RECOVERY"), service.start(MSISDN, PIN).body());
    verifyNoInteractions(otp, sms, enrolments);
  }

  @Test
  void noKey_orAPendingKeyOnly_routesToOtp() {
    accountWithPin();
    when(enrolments.open(eq(ACCOUNT), any(), any())).thenReturn(ENROLMENT);
    when(otp.issue(MSISDN)).thenReturn(new OtpEmission(MSISDN, "624010677000200", "123456"));
    for (String fact : List.of("NONE", "PENDING")) {
      when(device.state(ACCOUNT.toString())).thenReturn(reply(200, Map.of("state", fact)));
      Answer a = service.start(MSISDN, PIN);
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

    Answer a = service.start(MSISDN, PIN);
    assertEquals(503, a.status());
    assertTrue(a.retryAfter());
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
        new Answer(410, Map.of("reason", "OTP_EXPIRED"), false), service.otp(ENROLMENT, "123456"));
  }

  // ---- complete -------------------------------------------------------------------------------

  @Test
  void registered_returnsTheCodeOnce_andMovesToConfirm() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(reply(200, Map.of("deviceId", DEVICE, "recoveryCode", "ABCDEF")));
    when(enrolments.toConfirm(ENROLMENT, DEVICE)).thenReturn(true);

    Answer a = service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln");

    assertEquals(200, a.status());
    assertEquals(DEVICE, a.body().get("deviceId"));
    verify(device)
        .register(ACCOUNT.toString(), "cGs=", List.of("Y2VydA=="), "Y2hhbGxlbmdl", "c2ln");
  }

  @Test
  void softwareKey_isDeviceUnsupported() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(reply(422, Map.of("code", "DEVICE_UNSUPPORTED")));

    assertEquals(
        new Answer(422, Map.of("reason", "DEVICE_UNSUPPORTED"), false),
        service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln"));
  }

  @Test
  void pdpDownDuringComplete_keepsTheEnrolment() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(Reply.unreachable());

    Answer a = service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln");

    assertEquals(503, a.status());
    verify(enrolments, never()).consume(any(), anyString());
    verify(enrolments, never()).toConfirm(any(), anyString());
  }

  @Test
  void raceLostToAnotherBinding_is410_identicalToAnExpiredEnrolment() {
    enrolmentAt("BIND", NOW.plusSeconds(60));
    when(device.register(anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(reply(409, Map.of("code", "DEVICE_ALREADY_BOUND")));
    Answer race = service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln");
    verify(enrolments).consume(ENROLMENT, "BIND");

    enrolmentAt("BIND", NOW);
    Answer expired = service.complete(ENROLMENT, "cGs=", List.of("Y2VydA=="), "c2ln");

    assertEquals(new Answer(410, Map.of("reason", "ENROLMENT_EXPIRED"), false), race);
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
        new Answer(401, Map.of("reason", "RECOVERY_INVALID"), false),
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

    assertEquals(new Answer(410, Map.of("reason", "ENROLMENT_EXPIRED"), false), replay);
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
