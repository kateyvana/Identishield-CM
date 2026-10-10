package cm.identishield.pdp.signature;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Canonical signé d'un envoi (ADR-018, ISC-SAD §8.2), recalculé PAR LE SERVEUR à partir de la
 * requête : {@code accountId|type|amountXaf|beneficiaryId|nonce}, ordre figé, séparateur U+007C
 * sans espaces, UTF-8 sans BOM, montant décimal sans signe, nonce hexadécimal minuscule de 32
 * caractères ; aucun horodatage, jamais de JSON. Jumeau octet pour octet de {@code SignedPayload}
 * côté application (amendement M-07 d'ADR-018) : un champ vide, bordé d'espaces, contenant le
 * séparateur ou un retour à la ligne est refusé, car la chaîne n'a pas d'échappement.
 */
public final class CanonicalPayload {

  private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{32}$");
  private static final String SEPARATOR = "|";

  private CanonicalPayload() {}

  /**
   * @throws IllegalArgumentException si un champ ne peut pas entrer dans la chaîne sans ambiguïté
   */
  public static byte[] transfer(
      String accountId, String type, long amountXaf, String beneficiaryId, String nonce) {
    if (amountXaf < 1) {
      throw new IllegalArgumentException("amountXaf");
    }
    if (nonce == null || !NONCE.matcher(nonce).matches()) {
      throw new IllegalArgumentException("nonce");
    }
    String chain =
        String.join(
            SEPARATOR,
            field("accountId", accountId),
            field("type", type),
            Long.toString(amountXaf),
            field("beneficiaryId", beneficiaryId),
            nonce);
    return chain.getBytes(StandardCharsets.UTF_8);
  }

  private static String field(String name, String value) {
    boolean unsafe =
        value == null
            || value.isEmpty()
            || !value.equals(value.trim())
            || value.contains(SEPARATOR)
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    if (unsafe) {
      throw new IllegalArgumentException(name);
    }
    return value;
  }
}
