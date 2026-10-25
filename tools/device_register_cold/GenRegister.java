import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * D-B7-1 reproduction tool (lane B, 2026-10-25). Writes N self-consistent POST /device/register
 * bodies (first enrolment, TEE attestation chain built at runtime, challenge signed by the leaf
 * key) so the FULL PDP path runs: chain parsing, KeyDescription, challenge signature, PBKDF2 of
 * the recovery code, insertion. Chain construction copied from pdp/src/test TestAttestation
 * (nothing versioned, keys born here and discarded). Usage: java GenRegister.java <outDir> <n>
 */
public class GenRegister {
  static final String KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17";
  static final String ECDSA_SHA256 = "1.2.840.10045.4.3.2";
  static final int TEE = 1;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]);
    int n = Integer.parseInt(args[1]);
    Files.createDirectories(out);
    SecureRandom rnd = new SecureRandom();
    for (int i = 1; i <= n; i++) {
      KeyPair root = ecKey(), inter = ecKey(), leaf = ecKey();
      byte[] challenge = new byte[32];
      rnd.nextBytes(challenge);
      List<String> chain = chain(root, inter, leaf, realisticKeyDescription(challenge, TEE));
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initSign(leaf.getPrivate());
      s.update(challenge);
      String sig = b64(s.sign());
      String json =
          "{\"accountId\":\"" + UUID.randomUUID() + "\","
              + "\"publicKey\":\"" + b64(leaf.getPublic().getEncoded()) + "\","
              + "\"attestationChain\":[\"" + String.join("\",\"", chain) + "\"],"
              + "\"attestationChallenge\":\"" + b64(challenge) + "\","
              + "\"challengeSignature\":\"" + sig + "\","
              + "\"origin\":\"100.64.0.99\","
              + "\"installationId\":\"d-b7-1-" + i + "\"}";
      Files.writeString(out.resolve("body-" + i + ".json"), json);
    }
    System.out.println("written " + n + " bodies in " + out);
  }

  static KeyPair ecKey() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  static byte[] realisticKeyDescription(byte[] challenge, int level) {
    byte[] softwareEnforced =
        seq(
            explicit(701, integer(1_729_000_000_000L)),
            explicit(
                709,
                tlv(
                    0x04,
                    seq(
                        tlv(0x31, seq(tlv(0x04, ascii("cm.identishield.mobile")), integer(1))),
                        tlv(0x31, tlv(0x04, new byte[32]))))));
    byte[] hardwareEnforced =
        seq(
            explicit(1, tlv(0x31, integer(2), integer(3))),
            explicit(2, integer(3)),
            explicit(3, integer(256)),
            explicit(5, tlv(0x31, integer(4))),
            explicit(10, integer(1)),
            explicit(504, integer(2)),
            explicit(505, integer(0)),
            explicit(702, integer(0)),
            explicit(
                704,
                seq(
                    tlv(0x04, new byte[32]),
                    tlv(0x01, new byte[] {(byte) 0xFF}),
                    tlv(0x0A, small(0)),
                    tlv(0x04, new byte[32]))),
            explicit(705, integer(130_000)),
            explicit(706, integer(202_409)),
            explicit(718, integer(20_240_901)),
            explicit(719, integer(20_240_901)));
    return seq(
        integer(300),
        tlv(0x0A, small(level)),
        integer(300),
        tlv(0x0A, small(level)),
        tlv(0x04, challenge),
        tlv(0x04, new byte[0]),
        softwareEnforced,
        hardwareEnforced);
  }

  static List<String> chain(KeyPair root, KeyPair intermediate, KeyPair leaf, byte[] description)
      throws Exception {
    return List.of(
        b64(
            certificate(
                    "Android Keystore Key",
                    leaf.getPublic(),
                    "Intermediate",
                    intermediate.getPrivate(),
                    description)
                .getEncoded()),
        b64(
            certificate(
                    "Intermediate", intermediate.getPublic(), "Sandbox Root", root.getPrivate(), null)
                .getEncoded()),
        b64(
            certificate("Sandbox Root", root.getPublic(), "Sandbox Root", root.getPrivate(), null)
                .getEncoded()));
  }

  static X509Certificate certificate(
      String subject, PublicKey subjectKey, String issuer, PrivateKey issuerKey, byte[] keyDescription)
      throws Exception {
    byte[] extensions =
        keyDescription == null
            ? new byte[0]
            : tlv(0xA3, seq(seq(oid(KEY_DESCRIPTION_OID), tlv(0x04, keyDescription))));
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
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
  }

  static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

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

  static byte[] explicit(int n, byte[] inner) {
    ByteArrayOutputStream tag = new ByteArrayOutputStream();
    if (n < 31) {
      tag.write(0xA0 | n);
    } else {
      tag.write(0xBF);
      byte[] tmp = new byte[5];
      int k = tmp.length;
      tmp[--k] = (byte) (n & 0x7F);
      n >>= 7;
      while (n > 0) {
        tmp[--k] = (byte) (0x80 | (n & 0x7F));
        n >>= 7;
      }
      tag.write(tmp, k, tmp.length - k);
    }
    byte[] wrapped = tlv(0x00, inner);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(tag.toByteArray());
    out.write(wrapped, 1, wrapped.length - 1);
    return out.toByteArray();
  }

  static byte[] seq(byte[]... parts) {
    return tlv(0x30, parts);
  }

  static byte[] integer(long v) {
    return tlv(0x02, BigInteger.valueOf(v).toByteArray());
  }

  static byte[] small(int v) {
    return BigInteger.valueOf(v).toByteArray();
  }

  static byte[] bitString(byte[] v) {
    byte[] withPad = new byte[v.length + 1];
    System.arraycopy(v, 0, withPad, 1, v.length);
    return tlv(0x03, withPad);
  }

  static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  static byte[] name(String cn) {
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
