package cm.identishield.pdp.device;

import static cm.identishield.pdp.device.TestAttestation.SOFTWARE;
import static cm.identishield.pdp.device.TestAttestation.TEE;
import static cm.identishield.pdp.device.TestAttestation.chain;
import static cm.identishield.pdp.device.TestAttestation.ecKey;
import static cm.identishield.pdp.device.TestAttestation.keyDescription;
import static cm.identishield.pdp.device.TestAttestation.spki;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.device.DeviceEnrolment.Refusal;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
import cm.identishield.pdp.device.DeviceEnrolment.Registration;
import cm.identishield.pdp.device.EnrolmentThrottle.Key;
import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Audit SYMÉTRIQUE des refus de ré-enrôlement (décision du superviseur technique du 2026-10-25,
 * T-AC-16 ; ADR-030 amendé) : sur un compte lié, tout refus hors appelant bloqué écrit UNE entrée
 * {@code REENROL_REFUSED} avec UNE cause, la première dans l'ordre des vérifications
 * (DEVICE_UNSUPPORTED, REENROL_FROZEN, CODE_INVALID, ATTESTATION_INVALID, SIGNATURE_INVALID) ;
 * aucune au succès ni au THROTTLED ; jamais de secret ; pendant un gel, l'audit ne dit jamais que
 * le code était juste.
 *
 * <p>@covers ES-22 ; @covers ES-24 ; @covers AC-16
 */
class ReenrolRefusalAuditTest {

  private static final Instant T0 = Instant.parse("2026-10-25T12:00:00Z");
  private static final Duration FREEZE = Duration.ofHours(24);
  private static final byte[] CHALLENGE =
      "defi-attestation-symetrie-01".getBytes(StandardCharsets.UTF_8);
  private static final String CHALLENGE_B64 = Base64.getEncoder().encodeToString(CHALLENGE);
  private static final List<Key> CALLER = List.of(Key.origin("100.64.0.9"));
  private static final List<Key> COUNTED = List.of(Key.origin("100.64.0.9"), Key.account("acc-1"));

  private final KeyPair root = ecKey("secp256r1");
  private final KeyPair intermediate = ecKey("secp256r1");
  private final KeyPair device = ecKey("secp256r1");

  private final DeviceRegistry registry = mock(DeviceRegistry.class);
  private final RecoveryCodes codes = mock(RecoveryCodes.class);
  private final EnrolmentThrottle throttle = mock(EnrolmentThrottle.class);
  private final TrustStateMachine trust = mock(TrustStateMachine.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final DeviceEnrolment enrolment =
      new DeviceEnrolment(
          new AttestationVerifier(),
          registry,
          codes,
          throttle,
          trust,
          audit,
          mock(PlatformTransactionManager.class),
          Clock.fixed(T0, ZoneOffset.UTC));

  private static String sign(KeyPair key, byte[] data) {
    try {
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initSign(key.getPrivate());
      s.update(data);
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private List<String> hardwareChain() {
    return chain(root, intermediate, device, keyDescription(CHALLENGE, TEE, TEE));
  }

  private Registration register(List<String> chain, String signature, String recoveryCode) {
    return enrolment.register(
        "acc-1", spki(device.getPublic()), chain, CHALLENGE_B64, signature, recoveryCode, CALLER);
  }

  /** Compte lié (clé ACTIVE « dk-old », code « OLD123 »), non bloqué, jamais gelé. */
  private void boundAccount() {
    when(registry.findActive("acc-1"))
        .thenReturn(Optional.of(new DeviceRegistry.DeviceKey("dk-old", "acc-1", "cGs=")));
    when(codes.matches("dk-old", "OLD123")).thenReturn(true);
    when(throttle.lockedFor(any())).thenReturn(Optional.empty());
    when(trust.peek("acc-1")).thenReturn(Optional.empty());
    when(registry.registerPending(eq("acc-1"), eq(spki(device.getPublic())), any()))
        .thenReturn("dev-2");
    when(codes.issue(eq("dev-2"), any())).thenReturn("NEW456");
  }

  private void frozenUntil(Instant until) {
    when(trust.peek("acc-1"))
        .thenReturn(
            Optional.of(
                new Trust(
                    "acc-1",
                    State.TRUSTED,
                    T0,
                    "624010000000001",
                    "624010000000001",
                    null,
                    null,
                    null,
                    until,
                    null,
                    null)));
  }

  /** L'unique entrée d'audit écrite, dont la cause est [cause] ; comptée une fois ; sans secret. */
  private Map<String, Object> oneRefusalAudited(String cause) {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(audit).append(payload.capture(), eq(T0));
    Map<String, Object> p = payload.getValue();
    assertEquals("REENROL_REFUSED", p.get("event"));
    assertEquals("acc-1", p.get("accountId"));
    assertEquals(cause, p.get("cause"));
    assertEquals(T0.toString(), p.get("occurredAt"));
    assertFalse(p.toString().contains("OLD123"), "aucun secret dans l'audit");
    verify(throttle).failed(COUNTED);
    verify(registry, never()).registerPending(anyString(), anyString(), any());
    return p;
  }

  @Test
  void softwareKey_isAuditedAsDeviceUnsupported_firstInOrder_evenDuringAFreeze() {
    boundAccount();
    frozenUntil(T0.plus(FREEZE));
    Registration r =
        register(
            chain(root, intermediate, device, keyDescription(CHALLENGE, SOFTWARE, SOFTWARE)),
            sign(device, CHALLENGE),
            "WRONG1");
    assertEquals(new Refused(Refusal.DEVICE_UNSUPPORTED), r);
    Map<String, Object> p = oneRefusalAudited("DEVICE_UNSUPPORTED");
    assertEquals(T0.plus(FREEZE).toString(), p.get("reenrolFrozenUntil"), "l'état gelé est écrit");
  }

  @Test
  void softwareKey_outsideAFreeze_isAuditedTheSameWay_withoutAnyFreezeField() {
    boundAccount();
    register(
        chain(root, intermediate, device, keyDescription(CHALLENGE, SOFTWARE, SOFTWARE)),
        sign(device, CHALLENGE),
        "OLD123");
    Map<String, Object> p = oneRefusalAudited("DEVICE_UNSUPPORTED");
    assertFalse(p.containsKey("reenrolFrozenUntil"));
  }

  @Test
  void duringAFreeze_aWrongCode_isAuditedAsFrozen_neverAsCodeInvalid() {
    boundAccount();
    frozenUntil(T0.plus(FREEZE));
    Registration r = register(hardwareChain(), sign(device, CHALLENGE), "WRONG1");
    assertEquals(new Refused(Refusal.RECOVERY_INVALID), r);
    Map<String, Object> p = oneRefusalAudited("REENROL_FROZEN");
    assertEquals(T0.plus(FREEZE).toString(), p.get("reenrolFrozenUntil"));
  }

  @Test
  void duringAFreeze_aCorrectCode_isAuditedAsFrozen_theAuditNeverSaysTheCodeWasRight() {
    boundAccount();
    frozenUntil(T0.plus(FREEZE));
    assertEquals(
        new Refused(Refusal.RECOVERY_INVALID),
        register(hardwareChain(), sign(device, CHALLENGE), "OLD123"));
    oneRefusalAudited("REENROL_FROZEN");
  }

  @Test
  void aWrongCode_isAuditedAsCodeInvalid_evenIfTheSignatureIsWrongToo() {
    boundAccount();
    Registration r = register(hardwareChain(), "pas-une-signature", "WRONG1");
    assertEquals(new Refused(Refusal.RECOVERY_INVALID), r);
    Map<String, Object> p = oneRefusalAudited("CODE_INVALID");
    assertFalse(p.containsKey("reenrolFrozenUntil"));
  }

  @Test
  void aCorrectCode_withAnAttestationOfAnotherChallenge_isAuditedAsAttestationInvalid() {
    boundAccount();
    byte[] other = "un-autre-defi-0123456789".getBytes(StandardCharsets.UTF_8);
    Registration r =
        register(
            chain(root, intermediate, device, keyDescription(other, TEE, TEE)),
            sign(device, CHALLENGE),
            "OLD123");
    assertEquals(new Refused(Refusal.RECOVERY_INVALID), r);
    oneRefusalAudited("ATTESTATION_INVALID");
  }

  @Test
  void aCorrectCode_andAValidAttestation_withAWrongSignature_isAuditedAsSignatureInvalid() {
    boundAccount();
    Registration r = register(hardwareChain(), sign(device, "autre".getBytes()), "OLD123");
    assertEquals(new Refused(Refusal.RECOVERY_INVALID), r);
    oneRefusalAudited("SIGNATURE_INVALID");
  }

  @Test
  void aSuccess_writesNoAudit_andCountsNoFailure() {
    boundAccount();
    Registration r = register(hardwareChain(), sign(device, CHALLENGE), "OLD123");
    assertInstanceOf(Registered.class, r);
    verifyNoInteractions(audit);
    verify(throttle, never()).failed(any());
    verify(throttle).succeeded(COUNTED);
  }

  @Test
  void aThrottledCaller_isRefusedBeforeAnyCheck_withoutAudit() {
    boundAccount();
    when(throttle.lockedFor(any())).thenReturn(Optional.of(42L));
    Registration r = register(hardwareChain(), sign(device, CHALLENGE), "OLD123");
    assertEquals(new Refused(Refusal.THROTTLED, 42), r);
    verifyNoInteractions(audit);
    verify(codes, never()).matches(anyString(), anyString());
    verify(throttle, never()).failed(any());
  }

  @Test
  void aFirstEnrolment_isNotAReenrolment_andWritesNoAudit() {
    when(registry.findActive("acc-1")).thenReturn(Optional.empty());
    when(registry.registerPending(eq("acc-1"), eq(spki(device.getPublic())), any()))
        .thenReturn("dev-1");
    when(codes.issue(eq("dev-1"), any())).thenReturn("K7MQ3X");
    register(hardwareChain(), "pas-une-signature", null);
    register(hardwareChain(), sign(device, CHALLENGE), null);
    verifyNoInteractions(audit);
    verifyNoInteractions(throttle);
  }
}
