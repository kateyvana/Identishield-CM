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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.device.DeviceEnrolment.Refusal;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
import cm.identishield.pdp.device.DeviceEnrolment.Registration;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
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
  private final DeviceEnrolment enrolment =
      new DeviceEnrolment(
          new AttestationVerifier(),
          registry,
          codes,
          mock(PlatformTransactionManager.class),
          Clock.fixed(T0, ZoneOffset.UTC));

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
    return enrolment.register("acc-1", spki(device.getPublic()), chain, CHALLENGE_B64, signature);
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

  @Test
  void accountWithAnActiveKey_isRefused_invariantR1_withoutAnyWrite() {
    when(registry.hasActive("acc-1")).thenReturn(true);

    Registration r = register(hardwareChain(), sign(device, CHALLENGE));

    assertEquals(new Refused(Refusal.ALREADY_BOUND), r);
    verify(registry, never()).registerPending(anyString(), anyString(), any());
    verifyNoInteractions(codes);
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
  void rightCode_activatesTheKey() {
    when(codes.matches("dev-1", "K7MQ3X")).thenReturn(true);
    when(registry.activate("acc-1", "dev-1")).thenReturn(true);

    assertTrue(enrolment.activate("acc-1", "dev-1", "K7MQ3X"));
    verify(registry).activate("acc-1", "dev-1");
  }

  @Test
  void rightCode_butNotAPendingKeyOfThisAccount_isTheSameFailure() {
    when(codes.matches("dev-1", "K7MQ3X")).thenReturn(true);
    when(registry.activate("acc-2", "dev-1"))
        .thenReturn(false); // autre compte, ou clé non EN ATTENTE

    assertFalse(enrolment.activate("acc-2", "dev-1", "K7MQ3X"));
  }
}
