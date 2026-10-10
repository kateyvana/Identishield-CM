package cm.identishield.pdp.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lecteur DER minimal et STRICT, limité à ce que lit {@link AttestationVerifier} : une suite
 * d'éléments TLV de premier niveau. Aucune cryptographie ; la JDK n'expose pas d'API publique
 * ASN.1. Refuse au lieu de deviner : étiquette en forme longue, longueur indéfinie ou non minimale,
 * débordement, octets en trop.
 */
final class Der {

  /** Un élément : étiquette (un octet) et contenu. */
  record Tlv(int tag, byte[] value) {}

  static final int INTEGER = 0x02;
  static final int OCTET_STRING = 0x04;
  static final int ENUMERATED = 0x0A;
  static final int SEQUENCE = 0x30;

  private static final int MAX_LENGTH_BYTES = 4;

  private Der() {}

  /** Exactement un élément qui couvre tout le tampon. */
  static Tlv single(byte[] buf) {
    List<Tlv> all = elements(buf);
    if (all.size() != 1) {
      throw new MalformedDerException("expected exactly one element");
    }
    return all.get(0);
  }

  /** Les éléments successifs qui couvrent EXACTEMENT le tampon. */
  static List<Tlv> elements(byte[] buf) {
    List<Tlv> out = new ArrayList<>();
    int pos = 0;
    while (pos < buf.length) {
      int tag = buf[pos++] & 0xFF;
      if ((tag & 0x1F) == 0x1F) {
        throw new MalformedDerException("high-tag-number form not supported");
      }
      if (pos >= buf.length) {
        throw new MalformedDerException("truncated length");
      }
      int first = buf[pos++] & 0xFF;
      long length;
      if (first < 0x80) {
        length = first;
      } else {
        int count = first & 0x7F;
        if (count == 0) {
          throw new MalformedDerException("indefinite length is not DER");
        }
        if (count > MAX_LENGTH_BYTES || pos + count > buf.length) {
          throw new MalformedDerException("length out of range");
        }
        if ((buf[pos] & 0xFF) == 0) {
          throw new MalformedDerException("non-minimal length");
        }
        length = 0;
        for (int i = 0; i < count; i++) {
          length = (length << 8) | (buf[pos++] & 0xFF);
        }
        if (length < 0x80) {
          throw new MalformedDerException("non-minimal length");
        }
      }
      if (length > buf.length - pos) {
        throw new MalformedDerException("content overruns buffer");
      }
      int end = pos + (int) length;
      out.add(new Tlv(tag, Arrays.copyOfRange(buf, pos, end)));
      pos = end;
    }
    return out;
  }

  /** INTEGER ou ENUMERATED positif tenant sur 31 bits. */
  static int smallNonNegative(Tlv tlv) {
    byte[] v = tlv.value();
    if (v.length == 0 || v.length > 4 || (v[0] & 0x80) != 0) {
      throw new MalformedDerException("not a small non-negative integer");
    }
    if (v.length > 1 && v[0] == 0 && (v[1] & 0x80) == 0) {
      throw new MalformedDerException("non-minimal integer");
    }
    int n = 0;
    for (byte b : v) {
      n = (n << 8) | (b & 0xFF);
    }
    return n;
  }

  /** Structure DER hors du sous-ensemble attendu. */
  static final class MalformedDerException extends RuntimeException {
    MalformedDerException(String message) {
      super(message);
    }
  }
}
