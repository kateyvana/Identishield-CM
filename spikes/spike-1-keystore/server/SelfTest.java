import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * SPIKE-1 : auto-test du HARNAIS (pas une preuve appareil !). Genere une paire
 * EC P-256 en JVM, signe le canonical CAS-3, ecrit les fichiers d'entree du
 * Verifier. Sert uniquement a prouver que le verificateur fonctionne avant
 * l'arrivee des signatures du telephone. Code jetable.
 *
 * Execution : "$JAVA_HOME/bin/java" SelfTest.java  puis  Verifier.java selftest-input.txt selftest-otherkey.txt
 */
public final class SelfTest {

  public static void main(String[] args) throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair pair = gen.generateKeyPair();
    KeyPair other = gen.generateKeyPair(); // simule "l'autre appareil" pour N3

    String accountId = "7b2d4f6a-1c3e-4a5b-8d9f-0a1b2c3d4e5f";
    String type = "CASH_OUT";
    String amount = "25000";
    String beneficiary = "ben-000045";
    String nonce = "0123456789abcdef0123456789abcdef";
    String canonical = accountId + "|" + type + "|" + amount + "|" + beneficiary + "|" + nonce;

    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(pair.getPrivate());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    String sigB64 = Base64.getEncoder().encodeToString(s.sign());
    String pubB64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    String otherB64 = Base64.getEncoder().encodeToString(other.getPublic().getEncoded());

    Files.writeString(
        Path.of("selftest-input.txt"),
        String.join(
                "\n", pubB64, accountId, type, amount, beneficiary, nonce, sigB64)
            + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(Path.of("selftest-otherkey.txt"), otherB64 + "\n", StandardCharsets.UTF_8);
    System.out.println("selftest-input.txt et selftest-otherkey.txt ecrits");
  }
}
