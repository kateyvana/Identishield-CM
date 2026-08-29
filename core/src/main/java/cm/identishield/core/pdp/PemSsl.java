package cm.identishield.core.pdp;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Construit un {@link SSLContext} mutuel a partir de fichiers PEM, avec le seul JDK (aucune
 * bibliotheque hors stack, ADR-021 / §4). Fournit a la fois :
 *
 * <ul>
 *   <li>le magasin de CLES du client (certificat + cle privee du coeur), pour s'authentifier aupres
 *       du PDP ;
 *   <li>le magasin de CONFIANCE (CA locale), qui verifie la CHAINE du serveur.
 * </ul>
 *
 * La verification du NOM du serveur est activee separement sur le client HTTP
 * (endpointIdentificationAlgorithm=HTTPS) : chaine ET nom, les deux (OB-6).
 */
public final class PemSsl {

  private PemSsl() {}

  public static SSLContext mutualContext(Path clientChain, Path clientKey, Path caBundle)
      throws Exception {
    CertificateFactory cf = CertificateFactory.getInstance("X.509");

    // Magasin de confiance : la (ou les) autorite(s) racine acceptee(s).
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    int i = 0;
    for (Certificate ca : readCertificates(cf, caBundle)) {
      trust.setCertificateEntry("ca-" + i++, ca);
    }
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);

    // Magasin de cles : identite client du coeur (chaine + cle privee).
    List<X509Certificate> chain = readCertificates(cf, clientChain);
    PrivateKey key = readPkcs8EcKey(clientKey);
    KeyStore ks = KeyStore.getInstance("PKCS12");
    ks.load(null, null);
    ks.setKeyEntry("client", key, new char[0], chain.toArray(new Certificate[0]));
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, new char[0]);

    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
    return ctx;
  }

  private static List<X509Certificate> readCertificates(CertificateFactory cf, Path pem)
      throws Exception {
    try (InputStream in = Files.newInputStream(pem)) {
      List<X509Certificate> out = new ArrayList<>();
      for (Certificate c : cf.generateCertificates(in)) {
        out.add((X509Certificate) c);
      }
      return out;
    }
  }

  /** Lit une cle EC au format PKCS#8 non chiffre (openssl genpkey). */
  private static PrivateKey readPkcs8EcKey(Path pem) throws Exception {
    String body =
        Files.readString(pem)
            .replaceAll("-----BEGIN (EC )?PRIVATE KEY-----", "")
            .replaceAll("-----END (EC )?PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(body);
    return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
  }
}
