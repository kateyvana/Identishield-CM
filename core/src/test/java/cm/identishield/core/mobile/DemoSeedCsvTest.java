package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.core.mobile.DemoSeedCsv.Seed;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fichier des comptes de démonstration (carte #76) : lu en entier, les trois pièges du tableur
 * refusés avec la consigne, aucun PIN dans aucun message. Le CSV de test porte des PIN
 * manifestement factices (0000, 1111, 0042).
 */
class DemoSeedCsvTest {

  private static final String HEADER = "msisdn,holderName,pin,balanceXaf\n";

  @TempDir Path dir;

  private Path write(String name, byte[] content) throws IOException {
    Path p = dir.resolve(name);
    Files.write(p, content);
    return p;
  }

  private Path write(String content) throws IOException {
    return write("seed-" + System.nanoTime() + ".csv", content.getBytes(StandardCharsets.UTF_8));
  }

  private static String refusal(Path p) {
    return assertThrows(IllegalStateException.class, () -> DemoSeedCsv.read(p)).getMessage();
  }

  @Test
  void aValidFile_isReadEntirely_accentsAndQuotedCommasIncluded() {
    List<Seed> seeds =
        DemoSeedCsv.read(Path.of("src/test/resources/demo-seed/seed-test-comptes.csv"));
    assertEquals(3, seeds.size());
    assertEquals(new Seed("677001101", "Essimi NGUELE", "0000", 50000), seeds.get(0));
    assertEquals("MBALLA, Pauline", seeds.get(1).holderName(), "guillemets RFC 4180");
    assertEquals("Ékoé TSIMI", seeds.get(2).holderName(), "UTF-8");
    assertEquals("0042", seeds.get(2).pin(), "zéro de tête conservé");
    assertFalse(seeds.get(2).toString().contains("0042"), "toString ne montre jamais le PIN");
  }

  @Test
  void theThreeSpreadsheetTraps_areRefusedWithTheInstruction() throws IOException {
    String body = "677001101,Essimi NGUELE,0000,50000\n";
    byte[] withBom = ("﻿" + HEADER + body).getBytes(StandardCharsets.UTF_8);
    assertTrue(refusal(write("bom.csv", withBom)).contains("BOM"));

    String semicolons = "msisdn;holderName;pin;balanceXaf\n677001101;Essimi NGUELE;0000;50000\n";
    assertTrue(refusal(write(semicolons)).contains("séparateur « ; »"));

    String threeDigitPin = HEADER + "677001101,Essimi NGUELE,042,50000\n";
    String msg = refusal(write(threeDigitPin));
    assertTrue(msg.contains("ligne 2") && msg.contains("3 caractère(s)"), msg);
    assertFalse(msg.contains("042"), "jamais la valeur du PIN : " + msg);

    byte[] cp1252 =
        (HEADER + "677001101,Ékoé TSIMI,0000,50000\n").getBytes(Charset.forName("windows-1252"));
    assertTrue(refusal(write("cp1252.csv", cp1252)).contains("UTF-8"));
  }

  @Test
  void everyOtherDefect_namesFileLineAndCause_andNeverThePin() throws IOException {
    assertTrue(refusal(dir.resolve("absent.csv")).contains("absent"));
    assertTrue(refusal(write("")).contains("vide"));
    assertTrue(refusal(write("msisdn,name,pin,balance\n")).contains("en-tête attendu"));
    assertTrue(refusal(write(HEADER + "67700,X,0000,1\n")).contains("plage"));
    assertTrue(
        refusal(write(HEADER + "677001101,X,0000,1\n677001101,Y,1111,2\n")).contains("double"));
    assertTrue(refusal(write(HEADER + "677001101, ,0000,1\n")).contains("holderName vide"));
    assertTrue(refusal(write(HEADER + "677001101,X,0000,1 000\n")).contains("balanceXaf"));
    assertTrue(refusal(write(HEADER + "677001101,X,0000,12.5\n")).contains("balanceXaf"));
    assertTrue(refusal(write(HEADER + "677001101,X,0000\n")).contains("4 attendus"));
    assertTrue(refusal(write(HEADER + "677001101,\"X,0000,1\n")).contains("guillemet"));
    String msg = refusal(write(HEADER + "677001101,X,12a4,1\n"));
    assertTrue(msg.contains("ligne 2"), msg);
    assertFalse(msg.contains("12a4"), msg);
  }
}
