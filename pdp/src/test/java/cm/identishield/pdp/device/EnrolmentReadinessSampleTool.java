package cm.identishield.pdp.device;

import static cm.identishield.pdp.device.TestAttestation.TEE;
import static cm.identishield.pdp.device.TestAttestation.chain;
import static cm.identishield.pdp.device.TestAttestation.ecKey;
import static cm.identishield.pdp.device.TestAttestation.keyDescription;
import static cm.identishield.pdp.device.TestAttestation.spki;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * OUTIL, pas une porte : régénère l'échantillon PUBLIC de {@link EnrolmentReadiness} ({@code
 * ISC_READINESS_SAMPLE_OUT} = chemin de sortie). Chaîne de test à racine simulée, clé de niveau
 * TEE, défi et signature du défi. Les clés privées ne quittent pas ce test : la ressource ne
 * contient que des certificats, une clé publique et une signature.
 */
@EnabledIfEnvironmentVariable(named = "ISC_READINESS_SAMPLE_OUT", matches = ".+")
class EnrolmentReadinessSampleTool {

  @Test
  void writeThePublicSample() throws Exception {
    byte[] challenge = "echantillon-public-pret-par-construction".getBytes(StandardCharsets.UTF_8);
    KeyPair root = ecKey("secp256r1");
    KeyPair intermediate = ecKey("secp256r1");
    KeyPair device = ecKey("secp256r1");
    List<String> certs = chain(root, intermediate, device, keyDescription(challenge, TEE, TEE));
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(device.getPrivate());
    s.update(challenge);
    EnrolmentReadiness.Sample sample =
        new EnrolmentReadiness.Sample(
            certs,
            spki(device.getPublic()),
            Base64.getEncoder().encodeToString(challenge),
            Base64.getEncoder().encodeToString(s.sign()));
    assertEquals(
        AttestationVerifier.Outcome.VALID,
        new AttestationVerifier().verify(sample.chain(), sample.publicKey(), challenge));
    Files.writeString(
        Path.of(System.getenv("ISC_READINESS_SAMPLE_OUT")),
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(sample) + "\n");
  }
}
