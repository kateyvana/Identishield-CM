package cm.identishield.core.otp;

import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.HlrService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * OTP à usage unique (EF-03). Le cœur émet un OTP vers l'IMSI COURANT du numéro (lu du HLR, T2-10)
 * : c'est délibérément la faiblesse réelle — un SIM swap détourne l'OTP vers le nouvel IMSI, celui
 * de l'attaquant. La forme (longueur) et la durée sont des PARAMÈTRES versionnés (OTP.length,
 * OTP.ttl ; T2-03), jamais des constantes.
 *
 * <p><b>Sécurité :</b>
 *
 * <ul>
 *   <li>Le code est tiré d'un {@link SecureRandom} (jamais {@code Math.random}).
 *   <li>La base ne conserve qu'une EMPREINTE SHA-256 du code, jamais le clair.
 *   <li>Usage unique : la consommation est un UPDATE conditionnel atomique (voir {@link
 *       OtpRepository#consumeIfValid}). Expiration : {@code now + OTP.ttl}, depuis l'horloge
 *       injectée (ED-04).
 *   <li><b>Limite consignée</b> (décision du propriétaire, T2-12) : un OTP à 6 chiffres n'a que
 *       10^6 valeurs ; l'empreinte SHA-256 n'est PAS salée (le schéma T2-01 de {@code core.otp} n'a
 *       pas de colonne de sel), donc énumérable par qui lit la base. C'est acceptable ici : l'OTP
 *       est court PAR DESIGN (on reproduit la faiblesse réelle), éphémère (OTP.ttl) et à usage
 *       unique. L'empreinte empêche l'exposition triviale en clair, pas une attaque par lecture de
 *       base. Un durcissement (sel par ligne, HMAC à clé de service) relèverait d'un amendement de
 *       schéma.
 * </ul>
 *
 * <p>Revue ligne à ligne requise : credential à usage unique.
 */
// @implements EF-03
@Service
public class OtpService {

  private final OtpRepository otp;
  private final HlrService hlr;
  private final RuleSet ruleSet;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public OtpService(OtpRepository otp, HlrService hlr, RuleSet ruleSet, Clock clock) {
    this.otp = otp;
    this.hlr = hlr;
    this.ruleSet = ruleSet;
    this.clock = clock;
  }

  /**
   * Émet un OTP vers l'IMSI courant du numéro. Renvoie le code (pour l'émission simulée vers
   * l'IMSI) et l'IMSI cible. Lève si le numéro n'a pas d'IMSI enregistré au HLR.
   */
  public OtpEmission issue(String msisdn) {
    String targetImsi =
        hlr.currentImsi(msisdn)
            .orElseThrow(
                () -> new IllegalStateException("aucun IMSI courant au HLR pour " + msisdn));
    String code = generate(ruleSet.otpLength());
    Instant now = clock.instant();
    otp.issue(msisdn, targetImsi, sha256(code), now.plus(ruleSet.otpTtl()));
    return new OtpEmission(msisdn, targetImsi, code);
  }

  /** Vérifie et CONSOMME un OTP (usage unique). Distingue accepté / inconnu / expiré / consommé. */
  public OtpOutcome verify(String msisdn, String code) {
    String hash = sha256(code);
    Instant now = clock.instant();
    if (otp.consumeIfValid(msisdn, hash, now) == 1) {
      return OtpOutcome.ACCEPTED;
    }
    return otp.reasonFor(msisdn, hash, now);
  }

  /**
   * Code numérique de {@code length} chiffres, tiré d'un SecureRandom (zéros de tête conservés).
   */
  private String generate(int length) {
    int bound = (int) Math.pow(10, length);
    int value = random.nextInt(bound);
    return String.format("%0" + length + "d", value);
  }

  private static String sha256(String code) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponible", e);
    }
  }
}
