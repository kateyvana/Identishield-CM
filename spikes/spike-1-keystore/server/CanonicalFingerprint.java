import java.nio.charset.StandardCharsets;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * SPIKE-1, etape 1 (spec §4) : construit le canonical a ordre fixe et affiche
 * son empreinte SHA-256, pour comparaison octet a octet avec l'appareil Android.
 *
 * Canonical propose par ADR-018 :
 *   accountId | type | amountXaf | beneficiaryId | nonce
 * Separateur U+007C sans espaces ; UTF-8 sans BOM ; montant decimal sans signe
 * ni separateur ; nonce hex 32 minuscules ; ni retour ligne ni espaces de bord.
 *
 * Les TROIS cas couvrent les proprietes exigees par la spec §4 :
 *   CAS-1 : accountId UUID v4 minuscules ; amountXaf = 1
 *   CAS-2 : beneficiaryId avec caracteres non-ASCII ; amountXaf = 999999999
 *   CAS-3 : cas nominal (UUID v4, montant courant)
 *
 * Code jetable (spike). Execution : "$JAVA_HOME/bin/java" CanonicalFingerprint.java
 */
public final class CanonicalFingerprint {

  // Valeurs FIGEES : l'application Android du spike doit utiliser exactement les memes.
  static final String[][] CASES = {
    // accountId, type, amountXaf, beneficiaryId, nonce
    {
      "CAS-1",
      "5f1e7c1a-9d3b-4e6a-8c2f-0b7d9a4e6c1d",
      "P2P",
      "1",
      "ben-000045",
      "9f86d081884c7d659a2feaa0c55ad015"
    },
    {
      "CAS-2",
      "0e7c9b2a-5d1f-4c3a-9b6e-2f8a4d7c1e5b",
      "CASH_OUT",
      "999999999",
      "Aminaé Bénédicte-Ngô", // accents non-ASCII (U+00E9, U+00F4)
      "a3f1c2d4e5b6978810fedcba98765432"
    },
    {
      "CAS-3",
      "7b2d4f6a-1c3e-4a5b-8d9f-0a1b2c3d4e5f",
      "CASH_OUT",
      "25000",
      "ben-000045",
      "0123456789abcdef0123456789abcdef"
    },
  };

  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    for (String[] c : CASES) {
      String canonical = c[1] + "|" + c[2] + "|" + c[3] + "|" + c[4] + "|" + c[5];
      byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
      String digest = HexFormat.of().formatHex(sha256.digest(bytes));
      out.println(c[0] + " canonical  : [" + canonical + "]");
      out.println(c[0] + " octets UTF8: " + bytes.length);
      out.println(c[0] + " sha256     : " + digest);
      out.println();
    }
  }
}
