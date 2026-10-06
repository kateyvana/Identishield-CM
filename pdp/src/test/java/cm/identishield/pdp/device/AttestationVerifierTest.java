package cm.identishield.pdp.device;

import static cm.identishield.pdp.device.TestAttestation.SOFTWARE;
import static cm.identishield.pdp.device.TestAttestation.STRONGBOX;
import static cm.identishield.pdp.device.TestAttestation.TEE;
import static cm.identishield.pdp.device.TestAttestation.b64;
import static cm.identishield.pdp.device.TestAttestation.certificate;
import static cm.identishield.pdp.device.TestAttestation.ecKey;
import static cm.identishield.pdp.device.TestAttestation.integer;
import static cm.identishield.pdp.device.TestAttestation.keyDescription;
import static cm.identishield.pdp.device.TestAttestation.seq;
import static cm.identishield.pdp.device.TestAttestation.spki;
import static cm.identishield.pdp.device.TestAttestation.tlv;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cm.identishield.pdp.device.AttestationVerifier.Outcome;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Attestation de clé : chaîne liée, clé attestée = clé présentée, structure KeyDescription, niveau
 * matériel, liaison au défi du serveur. Racine supposée de confiance (racine simulée, limite
 * déclarée).
 */
class AttestationVerifierTest {

  private static final byte[] CHALLENGE =
      "défi-serveur-0123456789".getBytes(StandardCharsets.UTF_8);

  private final AttestationVerifier verifier = new AttestationVerifier();
  private final KeyPair root = ecKey("secp256r1");
  private final KeyPair intermediate = ecKey("secp256r1");
  private final KeyPair device = ecKey("secp256r1");

  /** Chaîne feuille → intermédiaire → racine, la feuille portant [description]. */
  private List<String> chain(KeyPair leafKey, byte[] description) {
    return List.of(
        b64(
            certificate(
                "Android Keystore Key",
                leafKey.getPublic(),
                "Intermediate",
                intermediate.getPrivate(),
                description)),
        b64(
            certificate(
                "Intermediate", intermediate.getPublic(), "Sandbox Root", root.getPrivate(), null)),
        b64(
            certificate(
                "Sandbox Root", root.getPublic(), "Sandbox Root", root.getPrivate(), null)));
  }

  private Outcome verify(List<String> chain) {
    return verifier.verify(chain, spki(device.getPublic()), CHALLENGE);
  }

  @Test
  void hardwareKey_boundToTheServerChallenge_isValid() {
    assertEquals(Outcome.VALID, verify(chain(device, keyDescription(CHALLENGE, TEE, TEE))));
    assertEquals(
        Outcome.VALID, verify(chain(device, keyDescription(CHALLENGE, STRONGBOX, STRONGBOX))));
  }

  @Test
  void anotherChallenge_evenOneByte_isRefused() {
    byte[] other = CHALLENGE.clone();
    other[0] ^= 0x01;
    assertEquals(
        Outcome.CHALLENGE_MISMATCH, verify(chain(device, keyDescription(other, TEE, TEE))));
    assertEquals(
        Outcome.CHALLENGE_MISMATCH,
        verifier.verify(
            chain(device, keyDescription(CHALLENGE, TEE, TEE)), spki(device.getPublic()), null));
  }

  @Test
  void softwareKey_isRefused_mobS02() {
    assertEquals(
        Outcome.SOFTWARE_KEY, verify(chain(device, keyDescription(CHALLENGE, SOFTWARE, TEE))));
    assertEquals(
        Outcome.SOFTWARE_KEY, verify(chain(device, keyDescription(CHALLENGE, TEE, SOFTWARE))));
  }

  @Test
  void attestedKeyMustBeThePresentedKey() {
    KeyPair other = ecKey("secp256r1");
    assertEquals(Outcome.KEY_MISMATCH, verify(chain(other, keyDescription(CHALLENGE, TEE, TEE))));
  }

  @Test
  void presentedKeyMustBeP256() {
    KeyPair p384 = ecKey("secp384r1");
    assertEquals(
        Outcome.NOT_EC_P256,
        verifier.verify(
            chain(p384, keyDescription(CHALLENGE, TEE, TEE)), spki(p384.getPublic()), CHALLENGE));
  }

  @Test
  void missingOrMalformedExtension_isRefused() {
    assertEquals(Outcome.NO_ATTESTATION_EXTENSION, verify(chain(device, null)));
    // 7 champs au lieu de 8.
    byte[] seven =
        seq(
            integer(200),
            tlv(0x0A, new byte[] {1}),
            integer(200),
            tlv(0x0A, new byte[] {1}),
            tlv(0x04, CHALLENGE),
            tlv(0x04, new byte[0]),
            seq());
    assertEquals(Outcome.MALFORMED_EXTENSION, verify(chain(device, seven)));
    // Le défi au mauvais type (INTEGER au lieu d'OCTET STRING).
    byte[] wrongTag =
        seq(
            integer(200),
            tlv(0x0A, new byte[] {1}),
            integer(200),
            tlv(0x0A, new byte[] {1}),
            integer(42),
            tlv(0x04, new byte[0]),
            seq(),
            seq());
    assertEquals(Outcome.MALFORMED_EXTENSION, verify(chain(device, wrongTag)));
    // Octets en trop après la SEQUENCE.
    byte[] valid = keyDescription(CHALLENGE, TEE, TEE);
    byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
    assertEquals(Outcome.MALFORMED_EXTENSION, verify(chain(device, trailing)));
    // Longueur indéfinie (interdite en DER).
    byte[] indefinite = {0x30, (byte) 0x80, 0x00, 0x00};
    assertEquals(Outcome.MALFORMED_EXTENSION, verify(chain(device, indefinite)));
  }

  @Test
  void brokenOrUnreadableChain_isRefused() {
    KeyPair forger = ecKey("secp256r1");
    List<String> forged =
        List.of(
            b64(
                certificate(
                    "Android Keystore Key",
                    device.getPublic(),
                    "Intermediate",
                    forger.getPrivate(),
                    keyDescription(CHALLENGE, TEE, TEE))),
            b64(
                certificate(
                    "Intermediate",
                    intermediate.getPublic(),
                    "Sandbox Root",
                    root.getPrivate(),
                    null)),
            b64(
                certificate(
                    "Sandbox Root", root.getPublic(), "Sandbox Root", root.getPrivate(), null)));
    assertEquals(Outcome.BROKEN_CHAIN, verify(forged));
    List<String> rootNotSelfSigned =
        chain(device, keyDescription(CHALLENGE, TEE, TEE)).subList(0, 2);
    assertEquals(Outcome.BROKEN_CHAIN, verify(rootNotSelfSigned));
    assertEquals(Outcome.MALFORMED_CHAIN, verify(List.of("pas un certificat")));
    assertEquals(Outcome.MALFORMED_CHAIN, verify(List.of()));
  }
}
