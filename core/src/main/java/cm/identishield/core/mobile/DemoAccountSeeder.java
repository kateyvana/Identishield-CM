package cm.identishield.core.mobile;

import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.hlr.HlrService;
import cm.identishield.core.mobile.DemoSeedCsv.Seed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Comptes de démonstration, profil {@code demo} SEULEMENT, jamais en production (carte B3a décision
 * 6 ; carte #76, décision de la propriétaire du 2026-10-24). Deux sources, lues et validées en
 * entier AVANT toute écriture, puis écrites en UNE transaction :
 *
 * <ul>
 *   <li>les comptes techniques du faux serveur, source unique partagée
 *       (tools/mobile-mock/fixtures.json : numéros fictifs, PIN de test publics, rôle de test dans
 *       {@code testRole}) ;
 *   <li>les comptes nominatifs de la démonstration, fichier HORS du dépôt car il porte des PIN
 *       ({@code core.demo.seed-csv-path}, monté par infra/compose.b3a.yml depuis
 *       ISC_DEMO_SEED_CSV), validé par {@link DemoSeedCsv}. Fichier absent ou invalide : le cœur ne
 *       démarre pas.
 * </ul>
 *
 * <p>Rejouable : un compte n'est créé que s'il manque (solde posé à la création seulement, pour ne
 * pas effacer l'activité d'une démonstration) ; le nom du titulaire est réaligné sur la source à
 * chaque démarrage (ED-11, migration 003) ; le PIN n'est posé que s'il est absent ; l'abonné HLR
 * reçoit un IMSI de structure MTN dérivé du numéro (624-01). Aucun PIN n'est journalisé : des
 * nombres seulement.
 */
@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoAccountSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoAccountSeeder.class);

  /** Colonne de la migration 003 : son absence est nommée, pas une erreur SQL obscure. */
  static final String HOLDER_NAME_PROBE_SQL = "SELECT holder_name FROM core.account LIMIT 0";

  static final String HOLDER_NAME_SQL = "UPDATE core.account SET holder_name = ? WHERE msisdn = ?";

  private final AccountRepository accounts;
  private final HlrService hlr;
  private final PinStore pins;
  private final PinHasher hasher;
  private final Clock clock;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Path fixtures;
  private final Path seedCsv;

  public DemoAccountSeeder(
      AccountRepository accounts,
      HlrService hlr,
      PinStore pins,
      PinHasher hasher,
      Clock clock,
      ObjectMapper json,
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      @Value("${core.demo.fixtures-path}") String fixtures,
      @Value("${core.demo.seed-csv-path}") String seedCsv) {
    this.accounts = accounts;
    this.hlr = hlr;
    this.pins = pins;
    this.hasher = hasher;
    this.clock = clock;
    this.json = json;
    this.jdbc = jdbc;
    this.tx = tx;
    this.fixtures = Path.of(fixtures);
    this.seedCsv = Path.of(seedCsv);
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    List<Seed> technical = technicalAccounts();
    List<Seed> named = DemoSeedCsv.read(seedCsv);
    Set<String> msisdns = new HashSet<>();
    technical.forEach(s -> msisdns.add(s.msisdn()));
    for (Seed s : named) {
      if (!msisdns.add(s.msisdn())) {
        throw new IllegalStateException(
            "comptes de démonstration : "
                + seedCsv.getFileName()
                + " : "
                + s.msisdn()
                + " est déjà un compte technique de "
                + fixtures.getFileName());
      }
    }
    probeHolderNameColumn();
    List<Seed> all = new ArrayList<>(technical);
    all.addAll(named);
    tx.executeWithoutResult(status -> all.forEach(this::seed));
    log.info(
        "amorçage démo : {} comptes techniques, {} comptes de démonstration, noms des titulaires alignés",
        technical.size(),
        named.size());
  }

  private List<Seed> technicalAccounts() throws java.io.IOException {
    JsonNode root = json.readTree(Files.readString(fixtures));
    List<Seed> out = new ArrayList<>();
    for (JsonNode a : root.path("accounts")) {
      out.add(
          new Seed(
              a.path("msisdn").asText(),
              a.path("holderName").asText(),
              a.hasNonNull("pin") ? a.path("pin").asText() : null,
              a.path("balanceXaf").asLong()));
    }
    return out;
  }

  private void probeHolderNameColumn() {
    try {
      jdbc.queryForList(HOLDER_NAME_PROBE_SQL);
    } catch (DataAccessException e) {
      throw new IllegalStateException(
          "comptes de démonstration : colonne core.account.holder_name absente : migration 003"
              + " (nom du titulaire) non appliquée",
          e);
    }
  }

  private void seed(Seed s) {
    accounts.insertIfAbsent(s.msisdn(), s.balanceXaf(), clock.instant());
    jdbc.update(HOLDER_NAME_SQL, s.holderName(), s.msisdn());
    hlr.ensureSubscriber(s.msisdn(), imsiFor(s.msisdn()));
    if (s.pin() != null) {
      pins.setIfAbsent(s.msisdn(), hasher.protect(s.pin()));
    }
  }

  static String imsiFor(String msisdn) {
    return "624010" + msisdn;
  }
}
