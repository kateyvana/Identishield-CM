package cm.identishield.pdp.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.time.VirtualClock;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.yaml.snakeyaml.Yaml;

/**
 * GET /trust/{accountId} (ADR-041, amendement du 2026-10-24) : {@code peek} rend le MÊME état que
 * {@code current} — échéances passées comprises — sans écrire ni état, ni transition, ni entrée
 * d'audit. Sur la vraie base (variable ISC_TEST_PG_URL), comme {@link TrustStateMachineDbTest}.
 *
 * <p>@covers EF-13
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class TrustPeekDbTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final String IMSI_VICTIM = "624010000000123";
  private static final String IMSI_SWAP = "624010000000999";
  private static final long ENTRY_BALANCE = 100_000;

  private static RuleSet rules;
  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager transactions;

  private final VirtualClock clock = new VirtualClock(T0);
  private final String acc = "acc-peek-" + UUID.randomUUID();
  private TrustStateMachine m;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void connect() throws Exception {
    try (InputStream in = Files.newInputStream(Path.of("../config/ruleset.yaml"))) {
      Map<String, Object> rs =
          (Map<String, Object>) new Yaml().<Map<String, Object>>load(in).get("ruleset");
      rules = new RuleSet((String) rs.get("version"), (Map<String, Object>) rs.get("parameters"));
    }
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            System.getenv("ISC_TEST_PG_URL"),
            System.getenv("ISC_TEST_PG_USER"),
            System.getenv("ISC_TEST_PG_PASSWORD"));
    jdbc = new JdbcTemplate(ds);
    transactions = new DataSourceTransactionManager(ds);
  }

  @BeforeEach
  void machine() {
    m =
        new TrustStateMachine(
            jdbc, transactions, new AuditLog(jdbc, new ObjectMapper()), clock, rules);
  }

  private int transitions() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM pdp.trust_transition WHERE account_id = ?", Integer.class, acc);
  }

  private int auditEntries() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM pdp.audit_entry WHERE payload ->> 'accountId' = ?",
        Integer.class,
        acc);
  }

  private String storedState() {
    return jdbc.queryForObject(
        "SELECT state FROM pdp.trust_state WHERE account_id = ?", String.class, acc);
  }

  @Test
  void anAccountNeverSeen_hasNoPeek_andNothingIsWritten() {
    assertTrue(m.peek(acc).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.trust_state WHERE account_id = ?", Integer.class, acc));
  }

  @Test
  void peekAfterAnExpiredProbation_saysTrusted_likeCurrent_butWritesNothing() {
    m.enrolled(acc, IMSI_VICTIM);
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    int tr = transitions();
    int au = auditEntries();
    clock.advance(rules.carImsi().plus(Duration.ofSeconds(1)));

    Trust peeked = m.peek(acc).orElseThrow();
    assertEquals(State.TRUSTED, peeked.state(), "échéance CAR_IMSI appliquée en mémoire");
    assertEquals(
        IMSI_SWAP, peeked.referenceImsi(), "mêmes règles : l'IMSI courant devient référence");
    assertEquals("PROBATION", storedState(), "la ligne n'est PAS réécrite");
    assertEquals(tr, transitions(), "aucune transition écrite");
    assertEquals(au, auditEntries(), "aucune entrée d'audit écrite");

    // La lecture n'a rien changé à ce que la prochaine décision constate et écrit.
    Trust settled = m.current(acc);
    assertEquals(peeked.state(), settled.state());
    assertEquals(peeked.referenceImsi(), settled.referenceImsi());
    assertEquals("TRUSTED", storedState());
    assertEquals(tr + 1, transitions(), "current, lui, écrit la transition");
  }

  @Test
  void peekAfterAnExpiredFreeze_saysTrusted_likeCurrent_butWritesNothing() {
    m.enrolled(acc, IMSI_VICTIM);
    Trust frozen = m.frozenByDecision(acc, "did-peek");
    assertEquals(State.FROZEN, frozen.state());
    int tr = transitions();
    int au = auditEntries();

    Trust still = m.peek(acc).orElseThrow();
    assertEquals(State.FROZEN, still.state(), "avant l'échéance : FROZEN, avec freezeUntil");
    assertEquals(frozen.freezeUntil(), still.freezeUntil());

    clock.advance(rules.ttlFreeze().plus(Duration.ofSeconds(1)));
    Trust peeked = m.peek(acc).orElseThrow();
    assertEquals(State.TRUSTED, peeked.state(), "TTL_FREEZE échu : TRUSTED en mémoire");
    assertEquals("FROZEN", storedState(), "la ligne n'est PAS réécrite");
    assertEquals(tr, transitions());
    assertEquals(au, auditEntries());
    assertEquals(peeked.state(), m.current(acc).state());
  }
}
