package cm.identishield.core.mobile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lecture et validation du fichier des comptes de démonstration (carte #76, décision de la
 * propriétaire du 2026-10-24). Le fichier vit HORS du dépôt : il porte des PIN. Il est lu en entier
 * et validé AVANT toute écriture ; au premier défaut, le cœur ne démarre pas, et le message nomme
 * le fichier, la ligne, le champ et la cause, JAMAIS la valeur d'un PIN.
 *
 * <p>Les trois pièges d'un export de tableur sont REFUSÉS, avec la consigne, jamais devinés : BOM
 * UTF-8 (ou encodage Windows-1252), séparateur « ; », zéro de tête d'un PIN supprimé (PIN de moins
 * de quatre chiffres). Guillemets CSV (RFC 4180) acceptés : un nom contenant une virgule est lu.
 *
 * <p>Un seul validateur : celui-ci. Aucun script parallèle n'applique les mêmes règles ailleurs.
 */
public final class DemoSeedCsv {

  /** Une ligne valide du fichier (le PIN n'apparaît jamais dans toString). */
  public record Seed(String msisdn, String holderName, String pin, long balanceXaf) {
    @Override
    public String toString() {
      return "Seed[" + msisdn + ", " + holderName + ", pin=****, " + balanceXaf + "]";
    }
  }

  static final String HEADER = "msisdn,holderName,pin,balanceXaf";
  private static final Pattern MSISDN = Pattern.compile("^67700[0-9]{4}$");
  private static final Pattern PIN = Pattern.compile("^[0-9]{4}$");
  private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
  private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private DemoSeedCsv() {}

  public static List<Seed> read(Path file) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (NoSuchFileException e) {
      throw refuse(
          file, 0, "fichier absent (ISC_DEMO_SEED_CSV dans infra/.env, chemin dans la VM)");
    } catch (IOException e) {
      throw refuse(file, 0, "fichier illisible : " + e.getClass().getSimpleName());
    }
    if (startsWithBom(bytes)) {
      throw refuse(file, 1, "BOM UTF-8 en tête : exporter en « CSV UTF-8 » sans BOM");
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw refuse(file, 0, "encodage non UTF-8 (export Windows-1252 ?) : exporter en CSV UTF-8");
    }
    List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
    if (lines.isEmpty()) {
      throw refuse(file, 1, "fichier vide : en-tête attendu « " + HEADER + " »");
    }
    String header = lines.get(0).strip();
    if (!HEADER.equals(header)) {
      if (header.contains(";")) {
        throw refuse(file, 1, "séparateur « ; » : exporter avec la virgule comme séparateur");
      }
      throw refuse(file, 1, "en-tête attendu « " + HEADER + " », lu « " + header + " »");
    }
    List<Seed> seeds = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 1; i < lines.size(); i++) {
      int line = i + 1;
      if (lines.get(i).isBlank()) {
        throw refuse(file, line, "ligne vide au milieu du fichier");
      }
      List<String> f = fields(file, line, lines.get(i));
      if (f.size() != 4) {
        throw refuse(file, line, f.size() + " champs lus, 4 attendus (" + HEADER + ")");
      }
      String msisdn = f.get(0).strip();
      String name = f.get(1).strip();
      String pin = f.get(2).strip();
      String balance = f.get(3).strip();
      if (!MSISDN.matcher(msisdn).matches()) {
        throw refuse(
            file, line, "msisdn hors de la plage 677000000–677009999 : « " + msisdn + " »");
      }
      if (!seen.add(msisdn)) {
        throw refuse(file, line, "msisdn en double dans le fichier : " + msisdn);
      }
      if (name.isEmpty()) {
        throw refuse(file, line, "holderName vide (" + msisdn + ")");
      }
      if (!PIN.matcher(pin).matches()) {
        // Jamais la valeur : seulement la longueur, qui désigne le piège du tableur.
        throw refuse(
            file,
            line,
            "pin de "
                + pin.length()
                + " caractère(s), 4 chiffres attendus ("
                + msisdn
                + ") : zéro de tête supprimé par le tableur ? formater la colonne en texte");
      }
      if (!DIGITS.matcher(balance).matches()) {
        throw refuse(
            file,
            line,
            "balanceXaf entier positif attendu, sans espace ni décimale (" + msisdn + ")");
      }
      seeds.add(new Seed(msisdn, name, pin, Long.parseLong(balance)));
    }
    return seeds;
  }

  private static boolean startsWithBom(byte[] b) {
    return b.length >= 3 && b[0] == BOM[0] && b[1] == BOM[1] && b[2] == BOM[2];
  }

  /** Champs d'une ligne CSV (RFC 4180) : virgule, guillemets doublés pour un guillemet. */
  static List<String> fields(Path file, int line, String raw) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cur.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    if (quoted) {
      throw refuse(file, line, "guillemet ouvert jamais fermé");
    }
    out.add(cur.toString());
    return out;
  }

  private static IllegalStateException refuse(Path file, int line, String cause) {
    return new IllegalStateException(
        "comptes de démonstration : "
            + file.getFileName()
            + (line > 0 ? " ligne " + line : "")
            + " : "
            + cause);
  }
}
