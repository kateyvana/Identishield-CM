package cm.identishield.pdp.device;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Fabrique, À L'EXÉCUTION, des chaînes d'attestation de test : aucun certificat n'est versionné
 * (CLAUDE.md §4). Encodage DER à la main des seules structures nécessaires, signature par
 * java.security, relecture par CertificateFactory : ce sont de vrais certificats X.509.
 */
final class TestAttestation {

  static final int SOFTWARE = 0;
  static final int TEE = 1;
  static final int STRONGBOX = 2;
  private static final String ECDSA_SHA256 = "1.2.840.10045.4.3.2";

  private TestAttestation() {}

  static KeyPair ecKey(String curve) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec(curve));
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** KeyDescription d'Android : 8 champs, AuthorizationList vides. */
  static byte[] keyDescription(byte[] challenge, int attestationLevel, int keyLevel) {
    return seq(
        integer(200),
        tlv(0x0A, small(attestationLevel)),
        integer(200),
        tlv(0x0A, small(keyLevel)),
        tlv(0x04, challenge),
        tlv(0x04, new byte[0]),
        seq(),
        seq());
  }

  /** Certificat X.509 v3 signé ECDSA-SHA256 ; [keyDescription] null = sans extension. */
  static X509Certificate certificate(
      String subject,
      PublicKey subjectKey,
      String issuer,
      PrivateKey issuerKey,
      byte[] keyDescription) {
    try {
      byte[] extensions =
          keyDescription == null
              ? new byte[0]
              : tlv(
                  0xA3,
                  seq(
                      seq(
                          oid(AttestationVerifier.KEY_DESCRIPTION_OID),
                          tlv(0x04, keyDescription))));
      byte[] tbs =
          seq(
              tlv(0xA0, integer(2)),
              integer(System.identityHashCode(subjectKey) & 0x7fffffff),
              seq(oid(ECDSA_SHA256)),
              name(issuer),
              seq(tlv(0x17, ascii("260101000000Z")), tlv(0x17, ascii("360101000000Z"))),
              name(subject),
              subjectKey.getEncoded(),
              extensions);
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initSign(issuerKey);
      s.update(tbs);
      byte[] der = seq(tbs, seq(oid(ECDSA_SHA256)), bitString(s.sign()));
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(der));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String b64(X509Certificate c) {
    try {
      return Base64.getEncoder().encodeToString(c.getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String spki(PublicKey k) {
    return Base64.getEncoder().encodeToString(k.getEncoded());
  }

  // ---- DER (écriture, tests seulement) -------------------------------------------------------

  static byte[] tlv(int tag, byte[]... parts) {
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      content.writeBytes(p);
    }
    byte[] value = content.toByteArray();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    int n = value.length;
    if (n < 0x80) {
      out.write(n);
    } else if (n < 0x100) {
      out.write(0x81);
      out.write(n);
    } else {
      out.write(0x82);
      out.write(n >> 8);
      out.write(n & 0xFF);
    }
    out.writeBytes(value);
    return out.toByteArray();
  }

  static byte[] seq(byte[]... parts) {
    return tlv(0x30, parts);
  }

  static byte[] integer(long v) {
    return tlv(0x02, BigInteger.valueOf(v).toByteArray());
  }

  private static byte[] small(int v) {
    return BigInteger.valueOf(v).toByteArray();
  }

  private static byte[] bitString(byte[] v) {
    byte[] withPad = new byte[v.length + 1];
    System.arraycopy(v, 0, withPad, 1, v.length);
    return tlv(0x03, withPad);
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] name(String cn) {
    return seq(tlv(0x31, seq(oid("2.5.4.3"), tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
  }

  static byte[] oid(String dotted) {
    String[] arcs = dotted.split("\\.");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(Integer.parseInt(arcs[0]) * 40 + Integer.parseInt(arcs[1]));
    for (int i = 2; i < arcs.length; i++) {
      long arc = Long.parseLong(arcs[i]);
      byte[] tmp = new byte[10];
      int k = tmp.length;
      tmp[--k] = (byte) (arc & 0x7F);
      arc >>= 7;
      while (arc > 0) {
        tmp[--k] = (byte) (0x80 | (arc & 0x7F));
        arc >>= 7;
      }
      out.write(tmp, k, tmp.length - k);
    }
    return tlv(0x06, out.toByteArray());
  }
}
