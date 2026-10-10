package cm.identishield.pdp.device;

import cm.identishield.pdp.device.Der.MalformedDerException;
import cm.identishield.pdp.device.Der.Tlv;
import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Vérification de l'attestation de clé Android présentée à la liaison (ISC-MOB-SPEC §12.1,
 * MOB-S-01, MOB-S-02, MOB-S-04). Contrôle, dans l'ordre :
 *
 * <ol>
 *   <li>chaîne lisible, chaque certificat signé par le suivant, dernier auto-signé ;
 *   <li>clé publique attestée (certificat feuille) = clé présentée, EC P-256 ;
 *   <li>extension KeyDescription (OID 1.3.6.1.4.1.11129.2.1.17) présente et bien formée ;
 *   <li>niveaux de sécurité de l'attestation et de la clé : TEE ou StrongBox, jamais logiciel ;
 *   <li>défi d'attestation = défi émis par le serveur (comparaison à temps constant).
 * </ol>
 *
 * <p><b>Limite déclarée (amendement M-09 d'ADR-018, PROPOSÉ) :</b> la racine de la chaîne n'est PAS
 * comparée aux racines matérielles de Google. Dans le bac à sable, elle est supposée de confiance
 * (racine simulée). La révocation et l'état de démarrage vérifié (RootOfTrust) ne sont pas
 * contrôlés.
 */
@Component
public class AttestationVerifier {

  /** Extension d'attestation de clé Android (KeyDescription). */
  static final String KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17";

  private static final int KEY_DESCRIPTION_FIELDS = 8;
  private static final int SECURITY_LEVEL_TEE = 1; // TrustedEnvironment
  private static final int SECURITY_LEVEL_STRONGBOX = 2;

  /** Issue de la vérification ; seul VALID autorise l'enregistrement de la clé. */
  public enum Outcome {
    VALID,
    MALFORMED_CHAIN,
    BROKEN_CHAIN,
    KEY_MISMATCH,
    NOT_EC_P256,
    NO_ATTESTATION_EXTENSION,
    MALFORMED_EXTENSION,
    SOFTWARE_KEY,
    CHALLENGE_MISMATCH
  }

  /**
   * @param chainBase64 certificats DER en base64, de la feuille vers la racine
   * @param publicKeyBase64 clé présentée, SubjectPublicKeyInfo DER en base64
   * @param expectedChallenge défi d'attestation émis par le serveur pour cet enrôlement
   */
  public Outcome verify(
      List<String> chainBase64, String publicKeyBase64, byte[] expectedChallenge) {
    List<X509Certificate> chain = parse(chainBase64);
    if (chain == null) {
      return Outcome.MALFORMED_CHAIN;
    }
    if (!linked(chain)) {
      return Outcome.BROKEN_CHAIN;
    }
    X509Certificate leaf = chain.get(0);
    ECPublicKey presented;
    try {
      presented = EcP256.parse(publicKeyBase64);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return Outcome.NOT_EC_P256;
    }
    if (!MessageDigest.isEqual(leaf.getPublicKey().getEncoded(), presented.getEncoded())) {
      return Outcome.KEY_MISMATCH;
    }
    byte[] extension = leaf.getExtensionValue(KEY_DESCRIPTION_OID);
    if (extension == null) {
      return Outcome.NO_ATTESTATION_EXTENSION;
    }
    List<Tlv> description;
    try {
      description = keyDescription(extension);
    } catch (MalformedDerException e) {
      return Outcome.MALFORMED_EXTENSION;
    }
    int attestationLevel = Der.smallNonNegative(description.get(1));
    int keyLevel = Der.smallNonNegative(description.get(3));
    if (!hardware(attestationLevel) || !hardware(keyLevel)) {
      return Outcome.SOFTWARE_KEY;
    }
    byte[] challenge = description.get(4).value();
    if (expectedChallenge == null || !MessageDigest.isEqual(challenge, expectedChallenge)) {
      return Outcome.CHALLENGE_MISMATCH;
    }
    return Outcome.VALID;
  }

  /**
   * KeyDescription ::= SEQUENCE { attestationVersion INTEGER, attestationSecurityLevel ENUMERATED,
   * keyMintVersion INTEGER, keyMintSecurityLevel ENUMERATED, attestationChallenge OCTET STRING,
   * uniqueId OCTET STRING, softwareEnforced AuthorizationList, hardwareEnforced AuthorizationList
   * }. La valeur d'extension est un OCTET STRING qui enveloppe cette SEQUENCE.
   */
  private static List<Tlv> keyDescription(byte[] extensionValue) {
    Tlv wrapper = Der.single(extensionValue);
    if (wrapper.tag() != Der.OCTET_STRING) {
      throw new MalformedDerException("extension value is not an OCTET STRING");
    }
    Tlv sequence = Der.single(wrapper.value());
    if (sequence.tag() != Der.SEQUENCE) {
      throw new MalformedDerException("KeyDescription is not a SEQUENCE");
    }
    List<Tlv> fields = Der.elements(sequence.value());
    int[] expected = {
      Der.INTEGER,
      Der.ENUMERATED,
      Der.INTEGER,
      Der.ENUMERATED,
      Der.OCTET_STRING,
      Der.OCTET_STRING,
      Der.SEQUENCE,
      Der.SEQUENCE
    };
    if (fields.size() != KEY_DESCRIPTION_FIELDS) {
      throw new MalformedDerException("KeyDescription must have 8 fields");
    }
    for (int i = 0; i < expected.length; i++) {
      if (fields.get(i).tag() != expected[i]) {
        throw new MalformedDerException("unexpected tag in KeyDescription field " + i);
      }
    }
    Der.smallNonNegative(fields.get(0));
    Der.smallNonNegative(fields.get(1));
    Der.smallNonNegative(fields.get(2));
    Der.smallNonNegative(fields.get(3));
    return fields;
  }

  private static boolean hardware(int level) {
    return level == SECURITY_LEVEL_TEE || level == SECURITY_LEVEL_STRONGBOX;
  }

  private static List<X509Certificate> parse(List<String> chainBase64) {
    if (chainBase64 == null || chainBase64.isEmpty()) {
      return null;
    }
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      List<X509Certificate> chain = new ArrayList<>();
      for (String b64 : chainBase64) {
        byte[] der = Base64.getDecoder().decode(b64);
        chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
      }
      return chain;
    } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
      return null;
    }
  }

  /** Chaque certificat signé par le suivant, avec émetteur = sujet ; le dernier auto-signé. */
  private static boolean linked(List<X509Certificate> chain) {
    try {
      for (int i = 0; i < chain.size(); i++) {
        X509Certificate cert = chain.get(i);
        X509Certificate issuer = i + 1 < chain.size() ? chain.get(i + 1) : cert;
        if (!cert.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
          return false;
        }
        cert.verify(issuer.getPublicKey());
      }
      return true;
    } catch (GeneralSecurityException e) {
      return false;
    }
  }
}
