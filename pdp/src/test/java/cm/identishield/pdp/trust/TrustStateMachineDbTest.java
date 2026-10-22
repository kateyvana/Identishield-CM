package cm.identishield.pdp.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.time.VirtualClock;
import cm.identishield.pdp.trust.TrustStateMachine.Alert;
import cm.identishield.pdp.trust.TrustStateMachine.FreezeCause;
import cm.identishield.pdp.trust.TrustStateMachine.Refused;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Machine à états de confiance du compte, sur une VRAIE base PostgreSQL construite par
 * infra/init/init.sql, sous le rôle applicatif isc_pdp_app (privilèges réels). Un test par
 * transition autorisée et un par transition interdite (arbitrages A1-1 à A1-7 de
 * REGISTRE_DECISIONS). Les durées viennent de config/ruleset.yaml, jamais du test.
 *
 * <p>Sans base (variable ISC_TEST_PG_URL absente, ex. make check sur le poste), la classe est
 * ignorée ; tools/verify_trust_state.sh la lance contre une base jetable dans la VM.
 *
 * <p>Exigences vérifiées :
 *
 * <p>@covers EF-13
 *
 * <p>@covers ES-01b
 *
 * <p>@covers ES-03c
 *
 * <p>@covers ES-27
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class TrustStateMachineDbTest {

  private static final Instant T0 = Instant.parse("2026-10-14T08:00:00Z");
  private static final String IMSI_VICTIM = "624010000000123";
  private static final String IMSI_SWAP = "624010000000999";
  private static final String IMSI_SWAP2 = "624010000000777";
  private static final Duration HOUR = Duration.ofHours(1);

  /** Solde figé à l'entrée en PROBATION (V4, carte A4) ; valeur de test sans incidence ici. */
  private static final long ENTRY_BALANCE = 100_000;

  private static RuleSet rules;
  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager transactions;

  private final VirtualClock clock = new VirtualClock(T0);
  private final String acc = "acc-a1-" + UUID.randomUUID();
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

  // ---- lecture des preuves en base ----

  /** Historique pdp.trust_transition du compte : « FROM>TO EVENT CAUSE @occurred_at ». */
  private List<String> transitions() {
    return jdbc.query(
        "SELECT from_state, to_state, event, cause, occurred_at FROM pdp.trust_transition"
            + " WHERE account_id = ? ORDER BY id",
        (rs, i) ->
            rs.getString(1)
                + ">"
                + rs.getString(2)
                + " "
                + rs.getString(3)
                + " "
                + rs.getString(4)
                + " @"
                + rs.getObject(5, java.time.OffsetDateTime.class).toInstant(),
        acc);
  }

  /** Événements du journal d'audit chaîné portant ce compte, dans l'ordre de la chaîne. */
  private List<String> audit() {
    return jdbc.queryForList(
        "SELECT payload ->> 'event' FROM pdp.audit_entry WHERE payload ->> 'accountId' = ?"
            + " ORDER BY chain_index",
        String.class,
        acc);
  }

  private static String t(String fromTo, String event, String cause, Instant at) {
    return fromTo + " " + event + " " + cause + " @" + at;
  }

  /** Transition interdite : refusée, et l'état, l'historique et le journal ne bougent pas. */
  private void assertRefused(Executable op) {
    Trust before = m.current(acc);
    List<String> history = transitions();
    List<String> journal = audit();
    assertThrows(Refused.class, op);
    assertEquals(before, m.current(acc));
    assertEquals(history, transitions());
    assertEquals(journal, audit());
  }

  private Instant probationEnd(Instant changedAt) {
    return changedAt.plus(rules.carImsi());
  }

  // ======================= transitions autorisées =======================

  @Test
  void a15_firstEnrolment_isTrusted_withTheObservedImsiAsReference() {
    assertEquals(State.TRUSTED, m.current(acc).state()); // compte jamais vu : TRUSTED (A1-5)
    Trust t = m.enrolled(acc, IMSI_VICTIM);
    assertEquals(State.TRUSTED, t.state());
    assertEquals(IMSI_VICTIM, t.referenceImsi());
    assertEquals(List.of(), transitions()); // état initial : aucune transition
    assertEquals(List.of(), audit());
  }

  @Test
  void imsiChange_movesTrustedToProbation_forCarImsi() {
    m.enrolled(acc, IMSI_VICTIM);
    Trust t = m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    assertEquals(State.PROBATION, t.state());
    assertEquals(probationEnd(T0), t.probationUntil());
    assertEquals(IMSI_VICTIM, t.referenceImsi()); // la référence ne change qu'à la sortie (A1-1)
    assertEquals(IMSI_SWAP, t.currentImsi());
    assertEquals(
        List.of(t("TRUSTED>PROBATION", "TRUST_PROBATION_ENTERED", "IMSI_CHANGE", T0)),
        transitions());
    assertEquals(List.of("TRUST_PROBATION_ENTERED"), audit()); // une seule entrée d'audit
  }

  @Test
  void imsiChange_onAnAccountNeverEnrolled_isProbationToo() {
    // Compte USSD (AC-02) : jamais enrôlé, TRUSTED par défaut (A1-5) ; CAR_IMSI après TOUT
    // changement (ES-03c). L'IMSI d'avant le changement devient la référence.
    Trust t = m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    assertEquals(State.PROBATION, t.state());
    assertEquals(IMSI_VICTIM, t.referenceImsi());
    assertEquals(List.of("TRUST_PROBATION_ENTERED"), audit());
  }

  @Test
  void secondImsiChange_inProbation_restartsCarImsi() {
    m.enrolled(acc, IMSI_VICTIM);
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    clock.advance(HOUR.multipliedBy(10));
    Instant second = clock.instant();
    Trust t = m.imsiChanged(acc, IMSI_SWAP, IMSI_SWAP2, second, ENTRY_BALANCE);
    assertEquals(State.PROBATION, t.state());
    assertEquals(probationEnd(second), t.probationUntil());
    assertEquals(IMSI_VICTIM, t.referenceImsi());
    assertEquals(
        t("PROBATION>PROBATION", "TRUST_PROBATION_ENTERED", "IMSI_CHANGE", second),
        transitions().get(1));
  }

  @Test
  void a11_carImsiExpiry_movesProbationToTrusted_andTheCurrentImsiBecomesTheReference() {
    m.enrolled(acc, IMSI_VICTIM);
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    clock.setInstant(probationEnd(T0).minusNanos(1000));
    assertEquals(State.PROBATION, m.current(acc).state()); // borne stricte : pas encore
    clock.setInstant(probationEnd(T0).plus(HOUR.multipliedBy(5))); // lecture tardive
    Trust t = m.current(acc);
    assertEquals(State.TRUSTED, t.state());
    assertEquals(IMSI_SWAP, t.referenceImsi());
    assertNull(t.probationUntil());
    // La transition est datée de l'échéance, pas de la lecture (ADR-019).
    assertEquals(
        t("PROBATION>TRUSTED", "TRUST_PROBATION_EXPIRED", "CAR_IMSI_EXPIRED", probationEnd(T0)),
        transitions().get(1));
    assertEquals(List.of("TRUST_PROBATION_ENTERED", "TRUST_PROBATION_EXPIRED"), audit());
  }

  @Test
  void freezeDecision_movesTrustedToFrozen_forTtlFreeze() {
    m.enrolled(acc, IMSI_VICTIM);
    Trust t = m.frozenByDecision(acc, UUID.randomUUID().toString());
    assertEquals(State.FROZEN, t.state());
    assertEquals(FreezeCause.FREEZE_DECISION, t.freezeCause());
    assertEquals(T0.plus(rules.ttlFreeze()), t.freezeUntil());
    assertEquals(
        List.of(t("TRUSTED>FROZEN", "TRUST_FROZEN", "FREEZE_DECISION", T0)), transitions());
    assertEquals(List.of("TRUST_FROZEN"), audit());
  }

  @Test
  void freezeDecision_movesProbationToFrozen_keepingTheCarImsiDeadline() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    clock.advance(HOUR);
    Trust t = m.frozenByDecision(acc, UUID.randomUUID().toString());
    assertEquals(State.FROZEN, t.state());
    assertEquals(probationEnd(T0), t.probationUntil());
    assertEquals(
        t("PROBATION>FROZEN", "TRUST_FROZEN", "FREEZE_DECISION", T0.plus(HOUR)),
        transitions().get(1));
  }

  @Test
  void a12a_frozenAtT0Plus1h_expiresAtT0Plus25h_backToProbationUntilTheOriginalEnd() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    clock.setInstant(T0.plus(HOUR));
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    Instant expiry = T0.plus(HOUR).plus(rules.ttlFreeze());
    clock.setInstant(expiry.minusNanos(1000));
    assertEquals(State.FROZEN, m.current(acc).state());
    clock.setInstant(expiry);
    Trust t = m.current(acc);
    assertEquals(State.PROBATION, t.state());
    assertEquals(probationEnd(T0), t.probationUntil()); // échéance d'origine conservée
    assertEquals(IMSI_VICTIM, t.referenceImsi());
    assertEquals(
        t("FROZEN>PROBATION", "TRUST_UNFROZEN", "TTL_FREEZE_EXPIRED", expiry),
        transitions().get(2));
  }

  @Test
  void a12b_freezeStartedAfterCarImsi_endsTrusted() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    Instant late = probationEnd(T0).plus(HOUR);
    clock.setInstant(late);
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    clock.setInstant(late.plus(rules.ttlFreeze()));
    Trust t = m.current(acc);
    assertEquals(State.TRUSTED, t.state());
    assertEquals(IMSI_SWAP, t.referenceImsi());
    assertEquals(
        List.of(
            t("TRUSTED>PROBATION", "TRUST_PROBATION_ENTERED", "IMSI_CHANGE", T0),
            t("PROBATION>TRUSTED", "TRUST_PROBATION_EXPIRED", "CAR_IMSI_EXPIRED", probationEnd(T0)),
            t("TRUSTED>FROZEN", "TRUST_FROZEN", "FREEZE_DECISION", late),
            t(
                "FROZEN>TRUSTED",
                "TRUST_UNFROZEN",
                "TTL_FREEZE_EXPIRED",
                late.plus(rules.ttlFreeze()))),
        transitions());
  }

  @Test
  void a12_releaseDuringProbation_returnsToProbation_withTheOriginalEnd() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    clock.setInstant(T0.plus(HOUR));
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    clock.setInstant(T0.plus(HOUR.multipliedBy(2)));
    Trust t = m.release(acc, "analyst-01");
    assertEquals(State.PROBATION, t.state());
    assertEquals(probationEnd(T0), t.probationUntil());
    assertEquals(
        t("FROZEN>PROBATION", "TRUST_UNFROZEN", "RELEASE", T0.plus(HOUR.multipliedBy(2))),
        transitions().get(2));
  }

  @Test
  void release_ofATrustedAccountFreeze_returnsToTrusted() {
    m.enrolled(acc, IMSI_VICTIM);
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    Trust t = m.release(acc, "analyst-01");
    assertEquals(State.TRUSTED, t.state());
    assertNull(t.freezeCause());
    assertEquals(List.of("TRUST_FROZEN", "TRUST_UNFROZEN"), audit());
  }

  @Test
  void a14_notMeOnSimAlert_freezesTheAccount_andReenrolments_withoutExpiry() {
    m.enrolled(acc, IMSI_VICTIM);
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    clock.advance(HOUR);
    Trust t = m.notMe(acc, Alert.SIM_CHANGE);
    assertEquals(State.FROZEN, t.state());
    assertEquals(FreezeCause.NOT_ME, t.freezeCause());
    assertNull(t.freezeUntil());
    assertEquals(T0.plus(HOUR).plus(rules.reenrolFreeze()), t.reenrolFrozenUntil());
    assertEquals(
        t("PROBATION>FROZEN", "TRUST_FROZEN", "NOT_ME", T0.plus(HOUR)), transitions().get(1));
    assertEquals(List.of("TRUST_PROBATION_ENTERED", "TRUST_FROZEN", "REENROL_FROZEN"), audit());
  }

  @Test
  void a13_notMeOnSimAlert_turnsADecisionFreezeIntoAFreezeThatNeverExpires() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    Trust t = m.notMe(acc, Alert.SIM_CHANGE);
    assertEquals(FreezeCause.NOT_ME, t.freezeCause());
    assertNull(t.freezeUntil());
    assertEquals(t("FROZEN>FROZEN", "TRUST_FROZEN", "NOT_ME", T0), transitions().get(2));
    clock.setInstant(T0.plus(rules.ttlFreeze()).plus(rules.carImsi()).plusSeconds(1));
    assertEquals(State.FROZEN, m.current(acc).state());
  }

  @Test
  void a13_notMeFreeze_isReleasedExplicitly_toTheStateInForceBeforeIt() {
    m.enrolled(acc, IMSI_VICTIM);
    m.notMe(acc, Alert.SIM_CHANGE);
    clock.advance(HOUR);
    Trust t = m.release(acc, "operator-01");
    assertEquals(State.TRUSTED, t.state());
    assertEquals(
        t("FROZEN>TRUSTED", "TRUST_UNFROZEN", "RELEASE", T0.plus(HOUR)), transitions().get(1));
  }

  @Test
  void a16_imsiChangeWhileFrozen_updatesReferenceAndDeadline_stateStaysFrozen() {
    m.enrolled(acc, IMSI_VICTIM);
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    clock.advance(HOUR);
    Instant changedAt = clock.instant();
    Trust t = m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, changedAt, ENTRY_BALANCE);
    assertEquals(State.FROZEN, t.state());
    assertEquals(IMSI_SWAP, t.referenceImsi());
    assertEquals(probationEnd(changedAt), t.probationUntil());
    assertEquals(
        t("FROZEN>FROZEN", "TRUST_PROBATION_ENTERED", "IMSI_CHANGE", changedAt),
        transitions().get(1));
    // A la sortie, A1-2 : PROBATION jusqu'à l'échéance du changement.
    clock.setInstant(T0.plus(rules.ttlFreeze()));
    Trust after = m.current(acc);
    assertEquals(State.PROBATION, after.state());
    assertEquals(probationEnd(changedAt), after.probationUntil());
  }

  // ======================= transitions interdites =======================

  @Test
  void probationComesOnlyFromAnImsiChange_neverFromEnrolmentOrReenrolment() {
    m.enrolled(acc, IMSI_VICTIM); // premier enrôlement
    m.enrolled(acc, IMSI_VICTIM); // ré-enrôlement
    m.enrolled(
        acc, IMSI_SWAP); // ré-enrôlement observé sous un autre IMSI : toujours pas une carence
    m.notMe(acc, Alert.REENROL_ATTEMPT);
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    m.release(acc, "analyst-01");
    assertEquals(State.TRUSTED, m.current(acc).state());
    assertEquals(
        0, transitions().stream().filter(s -> s.contains("TRUST_PROBATION_ENTERED")).count());
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, clock.instant(), ENTRY_BALANCE);
    assertEquals(State.PROBATION, m.current(acc).state());
  }

  @Test
  void enrolmentDuringProbation_neitherEndsNorRestartsIt() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    clock.advance(HOUR);
    Trust t = m.enrolled(acc, IMSI_SWAP); // S-13 : premier enrôlement de l'attaquant après swap
    assertEquals(State.PROBATION, t.state());
    assertEquals(probationEnd(T0), t.probationUntil());
    assertEquals(IMSI_VICTIM, t.referenceImsi());
    assertEquals(1, transitions().size());
  }

  @Test
  void a14_notMeOnReenrolAlert_freezesReenrolmentsOnly_theStateDoesNotChange() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    Trust t = m.notMe(acc, Alert.REENROL_ATTEMPT);
    assertEquals(State.PROBATION, t.state());
    assertNull(t.freezeCause());
    assertEquals(T0.plus(rules.reenrolFreeze()), t.reenrolFrozenUntil());
    assertEquals(1, transitions().size());
    assertEquals(List.of("TRUST_PROBATION_ENTERED", "REENROL_FROZEN"), audit());
  }

  @Test
  void release_ofATrustedAccount_isRefused() {
    m.enrolled(acc, IMSI_VICTIM);
    assertRefused(() -> m.release(acc, "analyst-01"));
  }

  @Test
  void release_cannotShortenAProbation() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    assertRefused(() -> m.release(acc, "analyst-01"));
    assertEquals(State.PROBATION, m.current(acc).state());
  }

  @Test
  void freezeDecision_onAFrozenAccount_isRefused() {
    m.enrolled(acc, IMSI_VICTIM);
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    assertRefused(() -> m.frozenByDecision(acc, UUID.randomUUID().toString()));
  }

  @Test
  void notMeOnSimAlert_onANotMeFrozenAccount_isRefused() {
    m.enrolled(acc, IMSI_VICTIM);
    m.notMe(acc, Alert.SIM_CHANGE);
    assertRefused(() -> m.notMe(acc, Alert.SIM_CHANGE));
  }

  @Test
  void sameImsi_isNotAChange_andIsRefused() {
    m.enrolled(acc, IMSI_VICTIM);
    assertRefused(() -> m.imsiChanged(acc, IMSI_VICTIM, IMSI_VICTIM, T0, ENTRY_BALANCE));
    assertEquals(State.TRUSTED, m.current(acc).state());
  }

  @Test
  void a13_notMeFreeze_neverExpiresOnItsOwn() {
    m.enrolled(acc, IMSI_VICTIM);
    m.notMe(acc, Alert.SIM_CHANGE);
    clock.setInstant(T0.plus(rules.ttlFreeze().multipliedBy(30)));
    Trust t = m.current(acc);
    assertEquals(State.FROZEN, t.state());
    assertEquals(1, transitions().size());
  }

  @Test
  void decisionFreeze_holdsUntilTtlFreeze() {
    m.enrolled(acc, IMSI_VICTIM);
    m.frozenByDecision(acc, UUID.randomUUID().toString());
    clock.setInstant(T0.plus(rules.ttlFreeze()).minusNanos(1000));
    assertEquals(State.FROZEN, m.current(acc).state());
    assertEquals(1, transitions().size());
  }

  @Test
  void lockedIsRefusedByTheDatabase_t05() {
    m.enrolled(acc, IMSI_VICTIM);
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE pdp.trust_state SET state = 'LOCKED' WHERE account_id = ?", acc));
    assertEquals(State.TRUSTED, m.current(acc).state());
  }

  @Test
  void theApplicationRoleCannotRewriteTheTransitionHistory() {
    m.imsiChanged(acc, IMSI_VICTIM, IMSI_SWAP, T0, ENTRY_BALANCE);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE pdp.trust_transition SET to_state = 'TRUSTED' WHERE account_id = ?", acc));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM pdp.trust_transition WHERE account_id = ?", acc));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM pdp.trust_state WHERE account_id = ?", acc));
    assertEquals(1, transitions().size());
  }
}
