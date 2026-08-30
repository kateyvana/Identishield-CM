package cm.identishield.core.pdp;

/**
 * Resultat d'une sonde du coeur vers un PDP (verification 4a/4b d'OB-6). Le motif distingue
 * explicitement un refus sur la CHAINE/signature d'un refus sur le NOM du serveur : les deux
 * controles doivent etre actifs.
 */
public record ProbeResult(Reason reason, int httpCode, String rawMessage) {

  public enum Reason {
    ACCEPTED,
    CHAIN_OR_SIGNATURE,
    NAME_MISMATCH,
    OTHER
  }

  public static ProbeResult accepted(int httpCode) {
    return new ProbeResult(Reason.ACCEPTED, httpCode, "");
  }

  public static ProbeResult rejected(Reason reason, String rawMessage) {
    return new ProbeResult(reason, 0, rawMessage);
  }

  public boolean isAccepted() {
    return reason == Reason.ACCEPTED;
  }
}
