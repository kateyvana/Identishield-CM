package cm.identishield.core.mobile;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PIN applicatif (carte B3a, décision 2) : seule une empreinte PBKDF2-HMAC-SHA256 à sel aléatoire
 * est conservée (colonnes de {@code core.account}), jamais le PIN. Comparaison en temps constant.
 * Aucune primitive réimplémentée : {@link SecretKeyFactory} et {@link SecureRandom} du JDK.
 *
 * <p>Pourquoi un nombre d'itérations BAS, et pas les 600 000 recommandés pour les mots de passe
 * (ADR-042) : le nombre est fixé par un BUDGET (une dérivation ≤ 50 ms sur la machine du banc),
 * parce que cette dérivation tourne sur les mêmes vCPU que le PDP, tenu à p95 &lt; 500 ms (ENF-01),
 * et que {@code /enrolment/start} la calcule pour tout appelant, même sur un numéro inconnu
 * (ES-22). Les itérations n'achètent presque rien pour un PIN : 4 chiffres font 10 000
 * possibilités, 6 chiffres un million ; si la table fuit, l'attaquant épuise TOUT l'espace, quel
 * que soit le nombre : les itérations n'ont jamais été la défense du PIN. Le sel empêche seulement
 * le précalcul commun à tous les comptes. La défense du PIN est le comptage des essais, qui N'EST
 * PAS ENCORE CONSTRUIT (carte B4, P-11 ; aucun paramètre nommé dans ruleset.yaml) : d'ici là, le
 * PIN n'est pas protégé contre l'essai en ligne (limite L9, ADR-042). Une fois l'appareil lié, le
 * PIN ne suffit jamais seul : la clé d'appareil est l'autre facteur.
 *
 * <p>Revue ligne à ligne requise : secret d'authentification (empreinte, comparaison).
 */
// @implements ES-22
@Component
@EnableConfigurationProperties(PinProperties.class)
public class PinHasher {

  /** Empreinte conservée : base64 de la clé dérivée, base64 du sel, nombre d'itérations. */
  public record ProtectedPin(String hash, String salt, int iterations) {}

  private final PinProperties props;
  private final SecureRandom random = new SecureRandom();

  public PinHasher(PinProperties props) {
    this.props = props;
  }

  public ProtectedPin protect(String pin) {
    byte[] salt = new byte[props.saltBytes()];
    random.nextBytes(salt);
    return new ProtectedPin(
        derive(pin, salt, props.iterations()),
        Base64.getEncoder().encodeToString(salt),
        props.iterations());
  }

  /**
   * Vrai si [pin] correspond à [stored]. Sans empreinte (compte inconnu ou sans PIN), une empreinte
   * est dérivée quand même : la durée ne dit pas si le compte existe (ES-22).
   */
  public boolean matches(String pin, ProtectedPin stored) {
    if (stored == null) {
      derive(pin, new byte[props.saltBytes()], props.iterations());
      return false;
    }
    String computed = derive(pin, Base64.getDecoder().decode(stored.salt()), stored.iterations());
    return MessageDigest.isEqual(
        Base64.getDecoder().decode(computed), Base64.getDecoder().decode(stored.hash()));
  }

  private String derive(String pin, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, props.keyBits());
    try {
      byte[] key =
          SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      return Base64.getEncoder().encodeToString(key);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("PBKDF2WithHmacSHA256 indisponible", e);
    } finally {
      spec.clearPassword();
    }
  }
}
