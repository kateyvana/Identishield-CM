package cm.identishield.pdp.device;

import cm.identishield.pdp.time.MonotonicTimer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Prêt PAR CONSTRUCTION étendu au chemin d'enrôlement (D-B7-1, décision du superviseur technique du
 * 2026-10-25) : le premier {@code POST /device/register} après un redémarrage dépassait le délai de
 * 1 000 ms du client du cœur, parce que le chemin attestation, PBKDF2 et ECDSA n'était encore ni
 * chargé ni compilé. Ce composant le parcourt ICI, à l'initialisation des singletons, donc AVANT
 * l'ouverture du port :
 *
 * <ol>
 *   <li>une vérification d'attestation par le vérificateur de production, sur un échantillon PUBLIC
 *       versionné en ressource (chaîne de test à racine simulée, aucun secret) ; le résultat est
 *       indifférent, seul le chemin compte ;
 *   <li>une dérivation PBKDF2 aux itérations de production, par {@link RecoveryCodes#matches} sur
 *       un appareil qui n'existe pas et un code aléatoire jeté : une lecture, aucune écriture, le
 *       même travail qu'un code faux ;
 *   <li>une vérification ECDSA P-256 de la signature de l'échantillon.
 * </ol>
 *
 * <p>Aucune écriture en base. Le journal ne porte qu'une durée, jamais une valeur.
 */
@Component
public class EnrolmentReadiness {

  private static final Logger log = LoggerFactory.getLogger(EnrolmentReadiness.class);
  static final String SAMPLE = "readiness/attestation-sample.json";

  /** Échantillon public : chaîne de certificats, clé publique, défi et signature du défi. */
  record Sample(List<String> chain, String publicKey, String challenge, String signature) {}

  /** Ce que l'initialisation a parcouru (pour les tests ; aucune valeur). */
  record Warmed(
      AttestationVerifier.Outcome attestation, boolean pbkdf2Ran, boolean signatureValid) {}

  private final Warmed warmed;

  public EnrolmentReadiness(AttestationVerifier verifier, RecoveryCodes codes, ObjectMapper json) {
    long started = MonotonicTimer.nanos();
    Sample s = load(json);
    byte[] challenge = Base64.getDecoder().decode(s.challenge());
    AttestationVerifier.Outcome attestation = verifier.verify(s.chain(), s.publicKey(), challenge);
    // nextBytes, jamais generateSeed : ce dernier peut attendre l'entropie du système au démarrage.
    SecureRandom random = new SecureRandom();
    byte[] device = new byte[8];
    byte[] code = new byte[6];
    random.nextBytes(device);
    random.nextBytes(code);
    codes.matches("readiness-" + HexFormat.of().formatHex(device), HexFormat.of().formatHex(code));
    boolean signatureValid = ecdsa(s, challenge);
    this.warmed = new Warmed(attestation, true, signatureValid);
    log.info(
        "chemin d'enrôlement prêt à l'initialisation (attestation, PBKDF2, ECDSA) en {} ms,"
            + " avant l'ouverture du port",
        (MonotonicTimer.nanos() - started) / 1_000_000);
  }

  Warmed warmed() {
    return warmed;
  }

  private static Sample load(ObjectMapper json) {
    try (InputStream in = EnrolmentReadiness.class.getClassLoader().getResourceAsStream(SAMPLE)) {
      if (in == null) {
        throw new IllegalStateException("ressource absente : " + SAMPLE);
      }
      return json.readValue(in, Sample.class);
    } catch (IOException e) {
      throw new UncheckedIOException("ressource illisible : " + SAMPLE, e);
    }
  }

  private static boolean ecdsa(Sample s, byte[] challenge) {
    try {
      Signature v = Signature.getInstance("SHA256withECDSA");
      v.initVerify(EcP256.parse(s.publicKey()));
      v.update(challenge);
      return v.verify(Base64.getDecoder().decode(s.signature()));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }
}
