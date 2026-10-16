package cm.identishield.pdp.device;

import static cm.identishield.pdp.device.TestAttestation.TEE;
import static cm.identishield.pdp.device.TestAttestation.b64;
import static cm.identishield.pdp.device.TestAttestation.certificate;
import static cm.identishield.pdp.device.TestAttestation.ecKey;
import static cm.identishield.pdp.device.TestAttestation.integer;
import static cm.identishield.pdp.device.TestAttestation.seq;
import static cm.identishield.pdp.device.TestAttestation.spki;
import static cm.identishield.pdp.device.TestAttestation.tlv;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cm.identishield.pdp.device.AttestationVerifier.Outcome;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-040 : la lecture de KeyDescription reste en DER STRICT, comme avec le lecteur maison. Chaque
 * encodage non canonique ci-dessous doit être refusé (MALFORMED_EXTENSION), quelle que soit la
 * bibliothèque qui lit l'extension.
 */
class AttestationStrictnessTest {

  private static final byte[] CHALLENGE = "défi-serveur-strict".getBytes(StandardCharsets.UTF_8);

  private final AttestationVerifier verifier = new AttestationVerifier();
  private final KeyPair root = ecKey("secp256r1");
  private final KeyPair device = ecKey("secp256r1");

  private Outcome verify(byte[] keyDescription) {
    List<String> chain =
        List.of(
            b64(
                certificate(
                    "Android Keystore Key",
                    device.getPublic(),
                    "Sandbox Root",
                    root.getPrivate(),
                    keyDescription)),
            b64(
                certificate(
                    "Sandbox Root", root.getPublic(), "Sandbox Root", root.getPrivate(), null)));
    return verifier.verify(chain, spki(device.getPublic()), CHALLENGE);
  }

  /** Les 8 champs de KeyDescription, chacun remplaçable par un encodage fautif. */
  private static byte[][] fields() {
    return new byte[][] {
      integer(200),
      tlv(0x0A, new byte[] {(byte) TEE}),
      integer(200),
      tlv(0x0A, new byte[] {(byte) TEE}),
      tlv(0x04, CHALLENGE),
      tlv(0x04, new byte[0]),
      seq(),
      seq()
    };
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  private static byte[] withField(int index, byte[] replacement) {
    byte[][] f = fields();
    f[index] = replacement;
    return seq(f);
  }

  @Test
  void theCanonicalEncoding_isValid() {
    assertEquals(Outcome.VALID, verify(seq(fields())));
  }

  @Test
  void everyNonCanonicalEncoding_isRefused() {
    byte[] content = concat(fields());
    Map<String, byte[]> cases = new LinkedHashMap<>();
    // Longueur en forme longue alors que la forme courte suffit (contenu < 128 octets).
    cases.put(
        "longueur non minimale",
        concat(new byte[] {0x30, (byte) 0x81, (byte) content.length}, content));
    // Longueur précédée d'un octet nul.
    cases.put(
        "longueur avec zéro de tête",
        concat(new byte[] {0x30, (byte) 0x82, 0x00, (byte) content.length}, content));
    // Longueur indéfinie (BER).
    cases.put(
        "longueur indéfinie", concat(new byte[] {0x30, (byte) 0x80}, content, new byte[] {0, 0}));
    // Octet en trop après la SEQUENCE.
    cases.put("octet en trop", concat(seq(fields()), new byte[] {0x00}));
    // INTEGER non minimal (200 = 0x00C8 est minimal ; 0x0000C8 ne l'est pas).
    cases.put("entier non minimal", withField(0, new byte[] {0x02, 0x03, 0x00, 0x00, (byte) 0xC8}));
    // ENUMERATED non minimal pour le niveau de sécurité.
    cases.put("énuméré non minimal", withField(1, new byte[] {0x0A, 0x02, 0x00, 0x01}));
    // Niveau de sécurité négatif.
    cases.put("niveau négatif", withField(3, new byte[] {0x0A, 0x01, (byte) 0xFF}));
    // Défi en OCTET STRING construite (BER), pas primitive.
    cases.put("chaîne d'octets construite", withField(4, tlv(0x24, tlv(0x04, CHALLENGE))));
    cases.forEach((name, bytes) -> assertEquals(Outcome.MALFORMED_EXTENSION, verify(bytes), name));
  }
}
