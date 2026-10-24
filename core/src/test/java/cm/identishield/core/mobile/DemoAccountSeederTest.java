package cm.identishield.core.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.hlr.HlrService;
import cm.identishield.core.transfer.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Comptes de test (carte B3a, décision 6) : l'amorçage n'existe PAS hors du profil demo, et la
 * seule source est le fichier de fixtures du faux serveur.
 */
class DemoAccountSeederTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
          .withBean(HlrService.class, () -> mock(HlrService.class))
          .withBean(PinStore.class, () -> mock(PinStore.class))
          .withBean(PinHasher.class, () -> mock(PinHasher.class))
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
          .withBean(TransactionTemplate.class, () -> runNow())
          .withPropertyValues(
              "core.demo.fixtures-path=../tools/mobile-mock/fixtures.json",
              "core.demo.seed-csv-path=src/test/resources/demo-seed/seed-test-comptes.csv")
          .withUserConfiguration(DemoAccountSeeder.class);

  /** Transaction factice : exécute le bloc tout de suite. */
  private static TransactionTemplate runNow() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)
                      inv.getArgument(0))
                  .accept(null);
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
    return tx;
  }

  @Test
  void theSeederDoesNotExist_withoutTheDemoProfile() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DemoAccountSeeder.class));
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(DemoAccountSeeder.class));
  }

  @Test
  void theSeederExists_underTheDemoProfile() {
    runner
        .withPropertyValues("spring.profiles.active=demo")
        .run(ctx -> assertThat(ctx).hasSingleBean(DemoAccountSeeder.class));
  }

  @Test
  void theDerivedImsi_isAnMtnImsiOf15Digits() {
    String imsi = DemoAccountSeeder.imsiFor("677000200");
    assertEquals(15, imsi.length());
    assertTrue(imsi.startsWith("62401"));
  }

  /**
   * Carte A5-00 (#57, #80) : le cœur n'a PLUS de PLACEHOLDER_IMSI, il refuse toute opération d'un
   * numéro sans IMSI dans le HLR. Ce test gardait auparavant « aucun IMSI de démonstration ne vaut
   * le placeholder du cœur » en lisant la constante par réflexion ; la constante a été retirée par
   * cette carte. Il garde maintenant deux invariants plus forts : (1) le cœur n'a aucun placeholder
   * (si quelqu'un en réintroduit un, ce test rougit) ; (2) aucun IMSI de démonstration ne vaut
   * l'ANCIEN littéral, qui survit dans les exports d'audit et l'historique : une collision y
   * resterait incompréhensible à déboguer. L'amorçage inscrit chaque compte dans le HLR ({@code
   * hlr.ensureSubscriber}) : les comptes de démonstration peuvent transiger sous A5-00.
   */
  @Test
  void noDemoImsi_equalsTheFormerPlaceholder_andTheCoreHasNone() throws Exception {
    assertThrows(
        NoSuchFieldException.class,
        () -> TransferService.class.getDeclaredField("PLACEHOLDER_IMSI"),
        "le cœur ne doit plus porter d'IMSI factice (A5-00)");
    String placeholder = "624010000000000"; // ancien PLACEHOLDER_IMSI, retiré par A5-00
    List<String> msisdns = new ArrayList<>(List.of("677000000", "677009999"));
    new ObjectMapper()
        .readTree(Files.readString(Path.of("../tools/mobile-mock/fixtures.json")))
        .path("accounts")
        .forEach(a -> msisdns.add(a.path("msisdn").asText()));
    assertTrue(msisdns.size() > 2, "les numéros des fixtures ont été lus");
    for (String msisdn : msisdns) {
      assertNotEquals(placeholder, DemoAccountSeeder.imsiFor(msisdn), msisdn);
    }
  }

  // ---- carte #76 : comptes techniques + fichier nominatif, une transaction, rejouable ----------

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final HlrService hlr = mock(HlrService.class);
  private final PinStore pins = mock(PinStore.class);
  private final PinHasher hasher = new PinHasher(new PinProperties(1_000, 16, 256));
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final TransactionTemplate tx = runNow();

  private DemoAccountSeeder seeder(String csv) {
    return new DemoAccountSeeder(
        accounts,
        hlr,
        pins,
        hasher,
        Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC),
        new ObjectMapper(),
        jdbc,
        tx,
        "../tools/mobile-mock/fixtures.json",
        csv);
  }

  @Test
  void seedsTechnicalAndNamedAccounts_inOneTransaction_namesRealignedPinsOnlyIfAbsent()
      throws Exception {
    seeder("src/test/resources/demo-seed/seed-test-comptes.csv").run(null);

    verify(tx).executeWithoutResult(any());
    // 5 comptes techniques + 3 du fichier : compte créé s'il manque, nom réaligné, abonné HLR.
    verify(accounts, times(8)).insertIfAbsent(anyString(), anyLong(), any());
    verify(jdbc, times(8)).update(eq(DemoAccountSeeder.HOLDER_NAME_SQL), anyString(), anyString());
    verify(jdbc).update(DemoAccountSeeder.HOLDER_NAME_SQL, "Hervé ESSOMBA", "677000200");
    verify(jdbc).update(DemoAccountSeeder.HOLDER_NAME_SQL, "Clarisse TCHOUAKE", "677009999");
    verify(jdbc).update(DemoAccountSeeder.HOLDER_NAME_SQL, "MBALLA, Pauline", "677001102");
    verify(hlr, times(8)).ensureSubscriber(anyString(), anyString());
    // PIN : seulement les comptes qui en ont un (2 techniques + 3 du fichier), jamais réécrit.
    verify(pins, times(5)).setIfAbsent(anyString(), any());
    verify(accounts).insertIfAbsent(eq("677001103"), eq(125000L), any());
  }

  @Test
  void aNamedAccountCollidingWithATechnicalOne_refusesEverything_beforeAnyWrite(@TempDir Path dir)
      throws Exception {
    Path csv = dir.resolve("seed.csv");
    Files.writeString(csv, "msisdn,holderName,pin,balanceXaf\n677000200,Quelqu'un,0000,1\n");
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> seeder(csv.toString()).run(null));
    assertTrue(e.getMessage().contains("677000200"), e.getMessage());
    assertTrue(e.getMessage().contains("compte technique"), e.getMessage());
    verifyNoInteractions(accounts, hlr, pins);
    verify(jdbc, never()).update(anyString(), any(), any());
  }

  @Test
  void aMissingHolderNameColumn_namesMigration003_andWritesNothing() throws Exception {
    when(jdbc.queryForList(DemoAccountSeeder.HOLDER_NAME_PROBE_SQL))
        .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("probe", "sql", null));
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> seeder("src/test/resources/demo-seed/seed-test-comptes.csv").run(null));
    assertTrue(e.getMessage().contains("migration 003"), e.getMessage());
    verifyNoInteractions(accounts, hlr, pins);
  }

  @Test
  void aMissingOrInvalidCsv_stopsTheCore_beforeAnyWrite(@TempDir Path dir) {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> seeder(dir.resolve("absent.csv").toString()).run(null));
    assertTrue(e.getMessage().contains("absent"), e.getMessage());
    verifyNoInteractions(accounts, hlr, pins, jdbc);
  }
}
