package cm.identishield.core.mobile;

import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.hlr.HlrService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Comptes de test du premier enrôlement (carte B3a, décision 6), profil {@code demo} SEULEMENT :
 * jamais en production. UNE SEULE SOURCE, lue aussi par le faux serveur :
 * tools/mobile-mock/fixtures.json (numéros de la plage fictive, PIN de test publics). Idempotent :
 * un compte, un abonné HLR ou un PIN déjà présents ne sont pas réécrits. Aucun PIN n'est
 * journalisé.
 *
 * <p>Les fixtures ne portent pas d'IMSI : l'abonné HLR reçoit un IMSI de structure MTN dérivé du
 * numéro (624-01, MSIN = 0 + numéro), pour que l'OTP du flux A ait un destinataire.
 */
@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoAccountSeeder implements ApplicationRunner {

  private final AccountRepository accounts;
  private final HlrService hlr;
  private final PinStore pins;
  private final PinHasher hasher;
  private final Clock clock;
  private final ObjectMapper json;
  private final Path fixtures;

  public DemoAccountSeeder(
      AccountRepository accounts,
      HlrService hlr,
      PinStore pins,
      PinHasher hasher,
      Clock clock,
      ObjectMapper json,
      @Value("${core.demo.fixtures-path}") String fixtures) {
    this.accounts = accounts;
    this.hlr = hlr;
    this.pins = pins;
    this.hasher = hasher;
    this.clock = clock;
    this.json = json;
    this.fixtures = Path.of(fixtures);
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    JsonNode root = json.readTree(Files.readString(fixtures));
    for (JsonNode a : root.path("accounts")) {
      String msisdn = a.path("msisdn").asText();
      accounts.insertIfAbsent(msisdn, a.path("balanceXaf").asLong(), clock.instant());
      hlr.ensureSubscriber(msisdn, imsiFor(msisdn));
      if (a.hasNonNull("pin")) {
        pins.setIfAbsent(msisdn, hasher.protect(a.path("pin").asText()));
      }
    }
  }

  static String imsiFor(String msisdn) {
    return "624010" + msisdn;
  }
}
