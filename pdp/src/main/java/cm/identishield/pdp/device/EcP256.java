package cm.identishield.pdp.device;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Clés publiques d'appareil : X.509 SubjectPublicKeyInfo base64, EC P-256 SEULEMENT (ADR-018). */
public final class EcP256 {

  private static final ECParameterSpec P256 = p256();

  private EcP256() {}

  /**
   * @throws GeneralSecurityException si l'encodage est invalide ou si la courbe n'est pas P-256
   * @throws IllegalArgumentException si le base64 est invalide
   */
  public static ECPublicKey parse(String spkiBase64) throws GeneralSecurityException {
    PublicKey key =
        KeyFactory.getInstance("EC")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64)));
    if (!(key instanceof ECPublicKey ec) || !isP256(ec.getParams())) {
      throw new GeneralSecurityException("public key is not EC P-256");
    }
    return ec;
  }

  static boolean isP256(ECParameterSpec spec) {
    return spec.getCurve().equals(P256.getCurve())
        && spec.getGenerator().equals(P256.getGenerator())
        && spec.getOrder().equals(P256.getOrder())
        && spec.getCofactor() == P256.getCofactor();
  }

  private static ECParameterSpec p256() {
    try {
      AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
      params.init(new ECGenParameterSpec("secp256r1"));
      return params.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("secp256r1 unavailable in this JDK", e);
    }
  }
}
