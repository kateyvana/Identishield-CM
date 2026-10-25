package cm.identishield.pdp.device;

import static cm.identishield.pdp.device.TestAttestation.SOFTWARE;
import static cm.identishield.pdp.device.TestAttestation.TEE;
import static cm.identishield.pdp.device.TestAttestation.chain;
import static cm.identishield.pdp.device.TestAttestation.ecKey;
import static cm.identishield.pdp.device.TestAttestation.keyDescription;
import static cm.identishield.pdp.device.TestAttestation.spki;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.device.DeviceEnrolment.Refusal;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
import cm.identishield.pdp.device.DeviceEnrolment.Registration;
import cm.identishield.pdp.device.EnrolmentThrottle.Key;
import cm.identishield.pdp.time.VirtualClock;
import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Gel des ré-enrôlements après « ce n'est pas moi » (ES-27, A1-4 ; décision du superviseur du
 * 2026-10-25, L-B5-2) : pendant {@code reenrolFrozenUntil}, /device/register rend EXACTEMENT le
 * refus d'un code faux (ES-22), compte la tentative (ES-24) et l'écrit dans l'audit avec sa cause ;
 * après l'échéance (horloge virtuelle), le même appel réussit.
 *
 * <p>@covers ES-27 ; @covers ES-22 ; @covers ES-24
 */
class DeviceEnrolmentFreezeTest {

  private static final Instant T0 = Instant.parse("2026-10-25T08:00:00Z");
  private static final Duration FREEZE = Duration.ofHours(24);
  private static final byte[] CHALLENGE =
      "defi-attestation-gel-0123".getBytes(StandardCharsets.UTF_8);
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
  private final VirtualClock clock = new VirtualClock(T0);
  private final DeviceEnrolment enrolment =
      new DeviceEnrolment(
          new AttestationVerifier(),
          registry,
          codes,
          throttle,
          trust,
          audit,
          mock(PlatformTransactionManager.class),
          clock);

  private String sign(KeyPair key, byte[] data) {
    try {
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initSign(key.getPrivate());
      s.update(data);
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Registration registerWith(List<String> attestationChain, String recoveryCode) {
    return enrolment.register(
        "acc-1",
        spki(device.getPublic()),
        attestationChain,
        CHALLENGE_B64,
        sign(device, CHALLENGE),
        recoveryCode,
        CALLER);
  }

  private Registration register(String recoveryCode) {
    return enrolment.register(
        "acc-1",
        spki(device.getPublic()),
        chain(root, intermediate, device, keyDescription(CHALLENGE, TEE, TEE)),
        CHALLENGE_B64,
        sign(device, CHALLENGE),
        recoveryCode,
        CALLER);
  }

  /**
   * Compte lié (clé ACTIVE « dk-old », code « OLD123 »), gelé pour les ré-enrôlements jusqu'à
   * T0+24h.
   */
  private void boundAndFrozenUntil(Instant until) {
    when(registry.findActive("acc-1"))
        .thenReturn(Optional.of(new DeviceRegistry.DeviceKey("dk-old", "acc-1", "cGs=")));
    when(codes.matches("dk-old", "OLD123")).thenReturn(true);
    when(throttle.lockedFor(any())).thenReturn(Optional.empty());
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
    when(registry.registerPending(eq("acc-1"), eq(spki(device.getPublic())), any()))
        .thenReturn("dev-2");
    when(codes.issue(eq("dev-2"), any())).thenReturn("NEW456");
  }

  @Test
  void duringTheFreeze_aCorrectCodeAndAValidAttestation_areRefusedExactlyLikeAWrongCode() {
    boundAndFrozenUntil(T0.plus(FREEZE));

    Registration frozen = register("OLD123");

    // Byte-identique au refus d'un code faux : même record, donc même corps HTTP et même code.
    assertEquals(new Refused(Refusal.RECOVERY_INVALID), frozen);
    // Toutes les vérifications ont tourné quand même (durée uniforme) : le PBKDF2 du code compris.
    verify(codes).matches("dk-old", "OLD123");
    verify(registry, never()).registerPending(anyString(), anyString(), any());
    verify(codes, never()).issue(anyString(), any());
    // Compté (ES-24, par origine et par compte) …
    verify(throttle).failed(COUNTED);
    // … et journalisé avec sa cause, l'échéance et l'instant, sans aucun secret.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(audit).append(payload.capture(), eq(T0));
    assertEquals("REENROL_REFUSED", payload.getValue().get("event"));
    assertEquals("REENROL_FROZEN", payload.getValue().get("cause"));
    assertEquals("acc-1", payload.getValue().get("accountId"));
    assertEquals(T0.plus(FREEZE).toString(), payload.getValue().get("reenrolFrozenUntil"));
  }

  @Test
  void duringTheFreeze_aWrongCode_getsTheSameRefusal_andIsCountedOnce() {
    boundAndFrozenUntil(T0.plus(FREEZE));
    Registration wrong = register("WRONG1");
    assertEquals(new Refused(Refusal.RECOVERY_INVALID), wrong);
    verify(throttle, times(1)).failed(COUNTED);
  }

  @Test
  void afterTheDeadline_theSameCall_isAcceptedAgain_withoutAnyAuditOfRefusal() {
    boundAndFrozenUntil(T0.plus(FREEZE));
    clock.advance(FREEZE.plus(Duration.ofSeconds(1)));

    Registration r = register("OLD123");

    assertInstanceOf(Registered.class, r);
    assertEquals(new Registered("dev-2", "NEW456"), r);
    verify(audit, never()).append(any(), any());
    verify(throttle, never()).failed(any());
    verify(throttle).succeeded(COUNTED);
  }

  @Test
  void anAccountTheMachineNeverSaw_isNotFrozen() {
    boundAndFrozenUntil(T0.plus(FREEZE));
    when(trust.peek("acc-1")).thenReturn(Optional.empty());
    assertInstanceOf(Registered.class, register("OLD123"));
    verify(audit, never()).append(any(), any());
  }

  @Test
  void duringTheFreeze_aSoftwareKey_isNamedDeviceUnsupported_exactlyAsOutsideTheFreeze() {
    // PR #110 : la clé logicielle est jugée AVANT le gel. Sa réponse parle de l'appareil, pas du
    // code, et ne doit pas changer avec l'état gelé (ES-22, AC-16 ; contrat : DEVICE_UNSUPPORTED
    // nommée même au ré-enrôlement). Symétrie (décision du 2026-10-25) : elle est auditée comme
    // tout refus, cause DEVICE_UNSUPPORTED — jamais REENROL_FROZEN, l'appareil est jugé d'abord.
    List<String> softwareChain =
        chain(root, intermediate, device, keyDescription(CHALLENGE, SOFTWARE, SOFTWARE));
    boundAndFrozenUntil(T0.plus(FREEZE));
    Registration frozen = registerWith(softwareChain, "OLD123");
    assertEquals(new Refused(Refusal.DEVICE_UNSUPPORTED), frozen);
    verify(throttle, times(1)).failed(COUNTED);

    // Hors gel : byte-identique.
    clock.advance(FREEZE.plus(Duration.ofSeconds(1)));
    Registration thawed = registerWith(softwareChain, "OLD123");
    assertEquals(frozen, thawed);
    verify(throttle, times(2)).failed(COUNTED);

    // Une entrée d'audit par refus, même cause des deux côtés.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(audit, times(2)).append(payload.capture(), any());
    for (Map<String, Object> p : payload.getAllValues()) {
      assertEquals("REENROL_REFUSED", p.get("event"));
      assertEquals("DEVICE_UNSUPPORTED", p.get("cause"));
    }
  }
}
