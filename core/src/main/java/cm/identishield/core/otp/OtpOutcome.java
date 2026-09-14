package cm.identishield.core.otp;

/** Résultat de la vérification d'un OTP. Les motifs sont distingués pour un message clair. */
public enum OtpOutcome {
  /** Code valide, consommé à l'instant (usage unique honoré). */
  ACCEPTED,
  /** Aucun OTP actif ne correspond à ce code (jamais émis, ou mauvais code). */
  REFUSED_UNKNOWN,
  /** OTP trouvé mais expiré. */
  REFUSED_EXPIRED,
  /** OTP déjà consommé (tentative de rejeu). */
  REFUSED_CONSUMED
}
