package cm.identishield.pdp.device;

import static cm.identishield.pdp.device.TestAttestation.STRONGBOX;
import static cm.identishield.pdp.device.TestAttestation.TEE;
import static cm.identishield.pdp.device.TestAttestation.chain;
import static cm.identishield.pdp.device.TestAttestation.ecKey;
import static cm.identishield.pdp.device.TestAttestation.realisticKeyDescription;
import static cm.identishield.pdp.device.TestAttestation.spki;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.device.AttestationVerifier.Outcome;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
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
 * Risque B2 (carte B3a) : BouncyCastle valide aussi le contenu des AuthorizationList, ce que
 * l'ancien lecteur ignorait. Une attestation RÉALISTE (champs KeyMint, étiquettes à numéro élevé,
 * SET OF triés, RootOfTrust) doit passer le vérificateur strict ET l'enregistrement. Si ce test
 * échoue, on s'arrête et on le rapporte : on n'assouplit RIEN dans le vérificateur pour le faire
 * passer.
 *
 * <p>@covers ES-25
 */
class RealisticAttestationTest {

  private static final byte[] CHALLENGE =
      "defi-attestation-b2-realiste".getBytes(StandardCharsets.UTF_8);
  private static final Instant T0 = Instant.parse("2026-10-21T08:00:00Z");

  private final KeyPair root = ecKey("secp256r1");
  private final KeyPair intermediate = ecKey("secp256r1");
  private final KeyPair device = ecKey("secp256r1");

  @Test
  void realisticKeyMintAuthorizationLists_areAccepted_teeAndStrongBox() {
    AttestationVerifier verifier = new AttestationVerifier();
    for (int level : new int[] {TEE, STRONGBOX}) {
      List<String> chain =
          chain(root, intermediate, device, realisticKeyDescription(CHALLENGE, level));
      assertEquals(Outcome.VALID, verifier.verify(chain, spki(device.getPublic()), CHALLENGE));
    }
  }

  @Test
  void realisticAttestation_goesThroughRegistration() throws Exception {
    DeviceRegistry registry = mock(DeviceRegistry.class);
    RecoveryCodes codes = mock(RecoveryCodes.class);
    when(registry.registerPending("acc-1", spki(device.getPublic()), T0)).thenReturn("dev-1");
    when(codes.issue("dev-1", T0)).thenReturn("K7MQ3X");
    DeviceEnrolment enrolment =
        new DeviceEnrolment(
            new AttestationVerifier(),
            registry,
            codes,
            mock(EnrolmentThrottle.class),
            mock(cm.identishield.pdp.trust.TrustStateMachine.class),
            mock(cm.identishield.pdp.audit.AuditLog.class),
            mock(PlatformTransactionManager.class),
            Clock.fixed(T0, ZoneOffset.UTC));
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(device.getPrivate());
    s.update(CHALLENGE);

    Object r =
        enrolment.register(
            "acc-1",
            spki(device.getPublic()),
            chain(root, intermediate, device, realisticKeyDescription(CHALLENGE, TEE)),
            Base64.getEncoder().encodeToString(CHALLENGE),
            Base64.getEncoder().encodeToString(s.sign()),
            null,
            List.of(EnrolmentThrottle.Key.origin("100.64.0.9")));

    assertInstanceOf(Registered.class, r);
  }
}
