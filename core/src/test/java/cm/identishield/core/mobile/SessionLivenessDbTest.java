package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.config.RuleSet;
import cm.identishield.core.mobile.SessionTokens.LiveSession;
import cm.identishield.core.time.VirtualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * P-09 sur une VRAIE base (migration 005, rôle isc_core_app), avec les durées du jeu de règles
 * versionné et une horloge virtuelle : le serveur tue le jeton après APP_SESSION_BACKGROUND_TTL
 * sans aucune requête, après APP_SESSION_TTL sans requête de l'USAGER (les interrogations
 * automatiques ne comptent pas), et après APP_SESSION_MAX_TTL quoi qu'il arrive ; un jeton mort
 * n'est jamais ranimé.
 *
 * <p>Sans base (ISC_TEST_PG_URL absente), la classe est ignorée ; tools/verify_device_enrolment.sh
 * la lance contre une base jetable dans la VM.
 *
 * <p>@covers ES-15
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class SessionLivenessDbTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final String DEVICE = "6f0a2c1e-0000-4000-8000-000000000001";
  private static JdbcTemplate jdbc;
  private static RuleSet ruleSet;
  private final VirtualClock clock = new VirtualClock(T0);
  private final SessionTokens tokens =
      new SessionTokens(
          jdbc,
          clock,
          ruleSet.appSessionTtl(),
          ruleSet.appSessionBackgroundTtl(),
          ruleSet.appSessionMaxTtl());

  @BeforeAll
  static void connect() throws Exception {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                System.getenv("ISC_TEST_PG_URL"),
                System.getenv("ISC_TEST_PG_USER"),
                System.getenv("ISC_TEST_PG_PASSWORD")));
    ruleSet =
        new Binder(
                ConfigurationPropertySources.from(
                    new YamlPropertySourceLoader()
                        .load("ruleset", new FileSystemResource("../config/ruleset.yaml"))))
            .bind("ruleset", RuleSet.class)
            .get();
  }

  private UUID account(String msisdn) {
    new AccountRepository(jdbc).insertIfAbsent(msisdn, 0, T0);
    return new AccountRepository(jdbc).findByMsisdn(msisdn).orElseThrow().id();
  }

  @Test
  void noRequestAtAll_theTokenDiesAfterTheBackgroundTtl_andIsNeverRevived() {
    UUID acc = account("677009201");
    String token = tokens.issue(acc, DEVICE).token();
    Duration background = ruleSet.appSessionBackgroundTtl();
    clock.advance(background.minusSeconds(1));
    assertEquals(new LiveSession(acc, DEVICE), tokens.touch(token, false).orElseThrow());
    clock.advance(background.plusSeconds(1));
    assertTrue(tokens.touch(token, true).isEmpty(), "arrière-plan dépassé : mort");
    clock.advance(Duration.ofSeconds(1));
    assertTrue(tokens.touch(token, true).isEmpty(), "jamais ranimé");
    assertTrue(tokens.touch("pas-un-jeton", true).isEmpty());
  }

  @Test
  void automaticPollsKeepTheTokenAlive_butNotTheUser_idleKillsIt() {
    UUID acc = account("677009202");
    String token = tokens.issue(acc, DEVICE).token();
    Duration idle = ruleSet.appSessionTtl();
    Duration poll =
        ruleSet.appSessionBackgroundTtl().dividedBy(2); // POLL_FOREGROUND < arrière-plan
    Instant deadline = T0.plus(idle);
    while (clock.instant().plus(poll).isBefore(deadline)) {
      clock.advance(poll);
      assertTrue(tokens.touch(token, false).isPresent(), "sondage : vivant à " + clock.instant());
    }
    clock.advance(poll);
    assertTrue(tokens.touch(token, false).isEmpty(), "inactivité dépassée malgré les sondages");
  }

  @Test
  void userActivityExtendsIdle_butNothingExtendsTheMaximum() {
    UUID acc = account("677009203");
    String token = tokens.issue(acc, DEVICE).token();
    Duration step = ruleSet.appSessionBackgroundTtl().dividedBy(2);
    Instant max = T0.plus(ruleSet.appSessionMaxTtl());
    int touches = 0;
    while (clock.instant().plus(step).isBefore(max)) {
      clock.advance(step);
      assertTrue(tokens.touch(token, true).isPresent(), "activité : vivant à " + clock.instant());
      touches++;
    }
    assertTrue(touches > 0);
    clock.advance(step);
    assertFalse(tokens.touch(token, true).isPresent(), "durée maximale : mort malgré l'activité");
  }
}
