package cm.identishield.pdp.device;

import static cm.identishield.pdp.device.TestAttestation.SOFTWARE;
import static cm.identishield.pdp.device.TestAttestation.TEE;
import static cm.identishield.pdp.device.TestAttestation.chain;
import static cm.identishield.pdp.device.TestAttestation.ecKey;
import static cm.identishield.pdp.device.TestAttestation.keyDescription;
import static cm.identishield.pdp.device.TestAttestation.spki;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.device.DeviceEnrolment.Refusal;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
import cm.identishield.pdp.device.DeviceEnrolment.Registration;
import cm.identishield.pdp.device.EnrolmentThrottle.Key;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Enregistrement EN ATTENTE puis activation de la clé (ADR-041) : la clé n'est enregistrée qu'avec
 * une attestation matérielle valide liée au défi du cœur et une signature du défi par la clé
 * présentée ; un compte qui a déjà une clé ACTIVE est refusé (invariant R1) ; l'activation exige le
 * code de récupération retapé et ne progresse jamais sur une erreur (D4).
 *
 * <p>@covers ES-14b
 *
 * <p>@covers ES-18
 *
 * <p>@covers ES-20
 *
 * <p>@covers ES-25
 */
class DeviceEnrolmentTest {

  private static final Instant T0 = Instant.parse("2026-10-21T08:00:00Z");
  private static final byte[] CHALLENGE =
      "defi-attestation-b3a-0123".getBytes(StandardCharsets.UTF_8);
  private static final String CHALLENGE_B64 = Base64.getEncoder().encodeToString(CHALLENGE);

  private final KeyPair root = ecKey("secp256r1");
  private final KeyPair intermediate = ecKey("secp256r1");
  private final KeyPair device = ecKey("secp256r1");

  private final DeviceRegistry registry = mock(DeviceRegistry.class);
  private final RecoveryCodes codes = mock(RecoveryCodes.class);
  private final EnrolmentThrottle throttle = mock(EnrolmentThrottle.class);
  private final DeviceEnrolment enrolment =
      new DeviceEnrolment(
          new AttestationVerifier(),
          registry,
          codes,
          throttle,
          mock(PlatformTransactionManager.class),
          Clock.fixed(T0, ZoneOffset.UTC));
  private static final List<Key> CALLER = List.of(Key.origin("100.64.0.9"));
  private static final List<Key> COUNTED =
      List.of(Key.origin("100.64.0.9"), Key.account("acc-1"));

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

  private Registration register(List<String> chain, String signature) {
    return register(chain, signature, null);
  }

  private Registration register(List<String> chain, String signature, String recoveryCode) {
    return enrolment.register(
        "acc-1", spki(device.getPublic()), chain, CHALLENGE_B64, signature, recoveryCode, CALLER);
  }

  /** Compte déjà lié : la clé ACTIVE « dk-old », dont le code est « OLD123 ». */
  private void boundAccount() {
    when(registry.findActive("acc-1"))
        .thenReturn(Optional.of(new DeviceRegistry.DeviceKey("dk-old", "acc-1", "cGs=")));
    when(codes.matches("dk-old", "OLD123")).thenReturn(true);
    when(throttle.lockedFor(any())).thenReturn(Optional.empty());
  }

  private List<String> hardwareChain() {
    return chain(root, intermediate, device, keyDescription(CHALLENGE, TEE, TEE));
  }

  @Test
  void validHardwareAttestation_registersTheKeyPending_andIssuesTheCodeOnce() {
    when(registry.registerPending("acc-1", spki(device.getPublic()), T0)).thenReturn("dev-1");
    when(codes.issue("dev-1", T0)).thenReturn("K7MQ3X");

    Registration r = register(hardwareChain(), sign(device, CHALLENGE));

    assertEquals(new Registered("dev-1", "K7MQ3X"), r);
    verify(registry).registerPending("acc-1", spki(device.getPublic()), T0);
    verify(registry, never()).activate(anyString(), anyString());
  }

  // ---- réenrôlement, invariant R1 (carte B3b) ------------------------------------------------

  @Test
  void boundAccount_withTheOldCode_andAValidAttestation_registersPending_theActiveKeyStays() {
    boundAccount();
    when(registry.registerPending("acc-1", spki(device.getPublic()), T0)).thenReturn("dev-2");
    when(codes.issue("dev-2", T0)).thenReturn("NEW456");
    Registration r = register(hardwareChain(), sign(device, CHALLENGE), "OLD123");
    assertEquals(new Registered("dev-2", "NEW456"), r);
    verify(registry, never()).revokeActive(anyString(), any());
    verify(registry, never()).activate(anyString(), anyString());
    verify(throttle).succeeded(COUNTED);
    verify(throttle, never()).failed(any());
  }

  @Test
  void boundAccount_wrongCode_noCode_badAttestation_badSignature_areTheSameRefusal_eachCounted() {
    boundAccount();
    String good = sign(device, CHALLENGE);
    Refused expected = new Refused(Refusal.RECOVERY_INVALID);
    assertEquals(expected, register(hardwareChain(), good, "WRONG1"));
    assertEquals(expected, register(hardwareChain(), good, null));
    byte[] other = "un-autre-defi-0123456789".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        expected,
        register(
            chain(root, intermediate, device, keyDescription(other, TEE, TEE)), good, "OLD123"));
    assertEquals(
        expected, register(hardwareChain(), sign(ecKey("secp256r1"), CHALLENGE), "OLD123"));
    assertEquals(expected, register(hardwareChain(), "pas-une-signature", "OLD123"));
    // Le PBKDF2 du code a tourné à CHAQUE essai, même quand l'attestation était fausse : la durée
    // ne désigne pas la faute.
    verify(codes, times(5)).matches(eq("dk-old"), anyString());
    verify(throttle, times(5)).failed(COUNTED);
    verify(registry, never()).registerPending(anyString(), anyString(), any());
    verify(codes, never()).issue(anyString(), any());
  }

  @Test
  void boundAccount_lockedCaller_isThrottledBeforeAnyVerification() {
    boundAccount();
    when(throttle.lockedFor(COUNTED)).thenReturn(Optional.of(120L));
    Registration r = register(hardwareChain(), sign(device, CHALLENGE), "OLD123");
    assertEquals(new Refused(Refusal.THROTTLED, 120), r);
    verify(codes, never()).matches(anyString(), anyString());
    verify(throttle, never()).failed(any());
  }

  @Test
  void boundAccount_softwareKey_isNamed_andCounted() {
    boundAccount();
    Registration r =
        register(
            chain(root, intermediate, device, keyDescription(CHALLENGE, SOFTWARE, SOFTWARE)),
            sign(device, CHALLENGE),
            "OLD123");
    assertEquals(new Refused(Refusal.DEVICE_UNSUPPORTED), r);
    verify(throttle).failed(COUNTED);
  }

  @Test
  void firstEnrolment_neverTouchesTheThrottle_norTheCodes() {
    when(registry.registerPending("acc-1", spki(device.getPublic()), T0)).thenReturn("dev-1");
    when(codes.issue("dev-1", T0)).thenReturn("K7MQ3X");
    register(hardwareChain(), sign(device, CHALLENGE));
    register(hardwareChain(), "pas-une-signature");
    verifyNoInteractions(throttle);
    verify(codes, never()).matches(anyString(), anyString());
  }

  @Test
  void softwareKey_isUnsupported_withoutAnyWrite() {
    Registration r =
        register(
            chain(root, intermediate, device, keyDescription(CHALLENGE, SOFTWARE, SOFTWARE)),
            sign(device, CHALLENGE));

    assertEquals(new Refused(Refusal.DEVICE_UNSUPPORTED), r);
    verify(registry, never()).registerPending(anyString(), anyString(), any());
    verifyNoInteractions(codes);
  }

  @Test
  void attestationForAnotherChallenge_isRejected() {
    byte[] other = "un-autre-defi-0123456789".getBytes(StandardCharsets.UTF_8);
    Registration r =
        register(
            chain(root, intermediate, device, keyDescription(other, TEE, TEE)),
            sign(device, CHALLENGE));

    assertEquals(new Refused(Refusal.ATTESTATION_REJECTED), r);
    verify(registry, never()).registerPending(anyString(), anyString(), any());
  }

  @Test
  void challengeSignedByAnotherKey_orNotSigned_isRejected() {
    KeyPair stranger = ecKey("secp256r1");
    assertEquals(
        new Refused(Refusal.ATTESTATION_REJECTED),
        register(hardwareChain(), sign(stranger, CHALLENGE)));
    byte[] otherData = "autre-chose".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        new Refused(Refusal.ATTESTATION_REJECTED),
        register(hardwareChain(), sign(device, otherData)));
    assertEquals(
        new Refused(Refusal.ATTESTATION_REJECTED), register(hardwareChain(), "pas-une-signature"));
    verify(registry, never()).registerPending(anyString(), anyString(), any());
  }

  @Test
  void wrongCode_neverActivates_theKeyStaysPending() {
    when(codes.matches("dev-1", "WRONG1")).thenReturn(false);

    assertFalse(enrolment.activate("acc-1", "dev-1", "WRONG1"));
    verify(registry, never()).activate(anyString(), anyString());
  }

  @Test
  void rightCode_revokesThePreviousActiveKey_thenActivates_inOneTransaction() {
    when(codes.matches("dev-1", "K7MQ3X")).thenReturn(true);
    when(registry.activate("acc-1", "dev-1")).thenReturn(true);

    assertTrue(enrolment.activate("acc-1", "dev-1", "K7MQ3X"));
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(registry);
    order.verify(registry).lockAccount("acc-1");
    order.verify(registry).revokeActive("acc-1", T0);
    order.verify(registry).activate("acc-1", "dev-1");
  }

  @Test
  void rightCode_butNotAPendingKeyOfThisAccount_isTheSameFailure() {
    when(codes.matches("dev-1", "K7MQ3X")).thenReturn(true);
    when(registry.activate("acc-2", "dev-1"))
        .thenReturn(false); // autre compte, ou clé non EN ATTENTE

    assertFalse(enrolment.activate("acc-2", "dev-1", "K7MQ3X"));
  }
}
