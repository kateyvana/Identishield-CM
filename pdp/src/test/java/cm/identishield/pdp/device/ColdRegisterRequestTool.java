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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * OUTIL de mesure D-B7-1 (ordre du superviseur technique du 2026-10-25, point 4), pas une porte :
 * ne s'exécute que si {@code ISC_DB71_OUT} désigne un fichier de sortie. Écrit le corps JSON d'un
 * {@code POST /device/register} de PREMIER enrôlement (compte jetable, jamais vu) dont la chaîne
 * d'attestation est bien formée et liée à la clé, mais dont le défi annoncé diffère de celui de la
 * chaîne : le PDP parcourt tout le chemin d'attestation (chaîne, extension, niveau matériel) puis
 * refuse en 422 {@code ATTESTATION_REJECTED}, SANS AUCUNE ÉCRITURE en base (premier enrôlement
 * refusé : ni clé, ni code, ni compteur, ni audit). Ne couvre donc ni la vérification ECDSA du
 * défi, ni le PBKDF2 du code de récupération, ni l'insertion.
 */
@EnabledIfEnvironmentVariable(named = "ISC_DB71_OUT", matches = ".+")
class ColdRegisterRequestTool {

  @Test
  void writeARefusedFirstEnrolmentBody() throws Exception {
    byte[] attested = "defi-atteste-db71-0123456789".getBytes(StandardCharsets.UTF_8);
    byte[] announced = "defi-annonce-db71-9876543210".getBytes(StandardCharsets.UTF_8);
    KeyPair root = ecKey("secp256r1");
    KeyPair intermediate = ecKey("secp256r1");
    KeyPair device = ecKey("secp256r1");
    List<String> attestation =
        chain(root, intermediate, device, keyDescription(attested, TEE, TEE));
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(device.getPrivate());
    s.update(announced);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("accountId", "acc-mesure-db71-" + UUID.randomUUID());
    body.put("publicKey", spki(device.getPublic()));
    body.put("attestationChain", attestation);
    body.put("attestationChallenge", Base64.getEncoder().encodeToString(announced));
    body.put("challengeSignature", Base64.getEncoder().encodeToString(s.sign()));
    body.put("origin", "100.64.0.71");

    // Le corps produit est bien refusé par le vrai vérificateur, et pour la raison attendue.
    assertEquals(
        AttestationVerifier.Outcome.CHALLENGE_MISMATCH,
        new AttestationVerifier().verify(attestation, spki(device.getPublic()), announced));

    Path out = Path.of(System.getenv("ISC_DB71_OUT"));
    Files.writeString(out, new ObjectMapper().writeValueAsString(body));
    System.out.println("DB71_BODY=" + out + " accountId=" + body.get("accountId"));
  }
}
