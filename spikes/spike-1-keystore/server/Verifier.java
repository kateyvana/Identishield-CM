import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * SPIKE-1, partie B (spec §5.B) : verificateur Java 21 autonome, sans Spring,
 * sans dependance hors JDK. Code jetable.
 *
 * Entree : un fichier UTF-8 de 7 lignes :
 *   1: clePubliqueBase64 (X.509 SubjectPublicKeyInfo)
 *   2: accountId   3: type   4: amountXaf   5: beneficiaryId   6: nonce
 *   7: signatureBase64 (ECDSA/SHA-256 sur le canonical)
 *
 * Sortie : empreinte SHA-256 du canonical, verdict nominal VERIFIEE/REFUSEE,
 * puis les 4 cas negatifs obligatoires, chacun devant afficher REFUSEE :
 *   N1 un caractere modifie dans le canonical
 *   N2 le montant modifie de 1
 *   N3 la signature verifiee contre une AUTRE cle publique (2e fichier en argument)
 *   N4 la signature tronquee d'un octet
 *
 * Execution : "$JAVA_HOME/bin/java" Verifier.java <entree.txt> [clePubliqueAutreAppareil.txt]
 */
public final class Verifier {

  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    if (args.length < 1) {
      out.println("usage: Verifier <entree.txt> [clePubliqueAutreAppareil.txt]");
      System.exit(2);
    }
    List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
    String pubB64 = lines.get(0).strip();
    String accountId = lines.get(1).strip();
    String type = lines.get(2).strip();
    String amount = lines.get(3).strip();
    String beneficiary = lines.get(4).strip();
    String nonce = lines.get(5).strip();
    String sigB64 = lines.get(6).strip();

    PublicKey pub = decodePublicKey(pubB64);
    byte[] signature = Base64.getDecoder().decode(sigB64);

    String canonical = canonical(accountId, type, amount, beneficiary, nonce);
    byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
    out.println("canonical  : [" + canonical + "]");
    out.println("octets UTF8: " + bytes.length);
    out.println("sha256     : " + sha256Hex(bytes));

    out.println("NOMINAL    : " + verdict(pub, bytes, signature));

    // N1 : un caractere modifie dans le canonical (premier caractere permute)
    byte[] n1 = bytes.clone();
    n1[0] = (byte) (n1[0] == 'x' ? 'y' : 'x');
    out.println("N1 caractere modifie          : " + verdict(pub, n1, signature));

    // N2 : le montant modifie de 1
    String amountPlusOne = Long.toString(Long.parseLong(amount) + 1);
    byte[] n2 =
        canonical(accountId, type, amountPlusOne, beneficiary, nonce)
            .getBytes(StandardCharsets.UTF_8);
    out.println("N2 montant +1                 : " + verdict(pub, n2, signature));

    // N3 : verification contre la cle publique d'un AUTRE appareil
    if (args.length >= 2) {
      String otherB64 = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8).strip();
      PublicKey other = decodePublicKey(otherB64);
      out.println("N3 cle de l'autre appareil    : " + verdict(other, bytes, signature));
    } else {
      out.println("N3 cle de l'autre appareil    : NON EXECUTE (2e cle non fournie)");
    }

    // N4 : signature tronquee d'un octet
    byte[] truncated = Arrays.copyOf(signature, signature.length - 1);
    out.println("N4 signature tronquee 1 octet : " + verdict(pub, bytes, truncated));
  }

  static String canonical(String a, String t, String m, String b, String n) {
    return a + "|" + t + "|" + m + "|" + b + "|" + n;
  }

  static PublicKey decodePublicKey(String b64) throws Exception {
    byte[] der = Base64.getDecoder().decode(b64);
    return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
  }

  static String sha256Hex(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  static String verdict(PublicKey pub, byte[] data, byte[] sig) {
    try {
      Signature v = Signature.getInstance("SHA256withECDSA");
      v.initVerify(pub);
      v.update(data);
      return v.verify(sig) ? "VERIFIEE" : "REFUSEE";
    } catch (Exception e) {
      // une signature malformee (tronquee) leve souvent SignatureException : REFUSEE
      return "REFUSEE (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
    }
  }
}
