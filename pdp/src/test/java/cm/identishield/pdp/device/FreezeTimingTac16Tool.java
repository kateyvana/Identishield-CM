package cm.identishield.pdp.device;

import static cm.identishield.pdp.device.TestAttestation.TEE;
import static cm.identishield.pdp.device.TestAttestation.chain;
import static cm.identishield.pdp.device.TestAttestation.ecKey;
import static cm.identishield.pdp.device.TestAttestation.keyDescription;
import static cm.identishield.pdp.device.TestAttestation.spki;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.device.DeviceEnrolment.Refusal;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registration;
import cm.identishield.pdp.device.EnrolmentThrottle.Key;
import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.Alert;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.yaml.snakeyaml.Yaml;

/**
 * OUTIL de mesure T-AC-16 du gel des ré-enrôlements (ordre du superviseur du 2026-10-25, point 4),
 * pas une porte : ne s'exécute que si {@code ISC_TAC16=1} ET une base est fournie ({@code
 * ISC_TEST_PG_URL}, base jetable). {@code DeviceEnrolment.register} est appelé DIRECTEMENT, avec
 * {@code RecoveryCodes} (itérations PBKDF2 de production, lues dans application.yml), {@code
 * DeviceRegistry}, {@code AuditLog} et {@code TrustStateMachine} RÉELS, et le throttle neutralisé
 * (mock : jamais bloqué, comptage sans effet). Deux comptes liés, code FAUX : l'un non gelé,
 * l'autre gelé par « ce n'est pas moi » (REENROL_ATTEMPT). N appels chacun, en alternance ; médiane
 * et p95 par cas, imprimées brutes. Aucun seuil n'est décidé ici : l'écart est DÉCLARÉ, la décision
 * appartient au superviseur (fenêtre statistique du T-AC-16).
 */
@EnabledIfEnvironmentVariable(named = "ISC_TAC16", matches = "1")
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class FreezeTimingTac16Tool {

  private static final Instant T0 = Instant.parse("2026-10-25T09:00:00Z");
  private static final int N = Integer.parseInt(System.getenv().getOrDefault("ISC_TAC16_N", "200"));
  private static final int WARMUP = 20;
  private static final byte[] CHALLENGE =
      "defi-attestation-tac16-0123".getBytes(StandardCharsets.UTF_8);
  private static final String CHALLENGE_B64 = Base64.getEncoder().encodeToString(CHALLENGE);
  private static final List<Key> CALLER = List.of(Key.origin("100.64.0.16"));

  @SuppressWarnings("unchecked")
  private static RecoveryCodeProperties productionPbkdf2() throws Exception {
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
      Map<String, Object> root = new Yaml().load(in);
      Map<String, Object> isc = (Map<String, Object>) root.get("isc");
      Map<String, Object> rc = (Map<String, Object>) isc.get("recovery-code");
      Map<String, Object> p = (Map<String, Object>) rc.get("pbkdf2");
      return new RecoveryCodeProperties(
          (Integer) p.get("iterations"),
          (Integer) p.get("salt-bytes"),
          (Integer) p.get("key-bits"));
    }
  }

  private static String sign(KeyPair key, byte[] data) throws Exception {
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(key.getPrivate());
    s.update(data);
    return Base64.getEncoder().encodeToString(s.sign());
  }

  private static long percentile(long[] sorted, double q) {
    int i = (int) Math.ceil(q * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
  }

  @Test
  void measure_wrongCode_frozenVersusNotFrozen() throws Exception {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            System.getenv("ISC_TEST_PG_URL"),
            System.getenv("ISC_TEST_PG_USER"),
            System.getenv("ISC_TEST_PG_PASSWORD"));
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    DataSourceTransactionManager tm = new DataSourceTransactionManager(ds);
    RuleSet rules = TestRuleSet.load();
    RecoveryCodeProperties props = productionPbkdf2();
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    AuditLog audit = new AuditLog(jdbc, new ObjectMapper());
    TrustStateMachine trust = new TrustStateMachine(jdbc, tm, audit, clock, rules);
    DeviceRegistry registry = new DeviceRegistry(jdbc);
    RecoveryCodes codes = new RecoveryCodes(jdbc, rules, props);
    EnrolmentThrottle neutralised = mock(EnrolmentThrottle.class); // jamais bloqué, sans effet
    DeviceEnrolment enrolment =
        new DeviceEnrolment(
            new AttestationVerifier(), registry, codes, neutralised, trust, audit, tm, clock);

    KeyPair root = ecKey("secp256r1");
    KeyPair intermediate = ecKey("secp256r1");
    KeyPair device = ecKey("secp256r1");
    List<String> validChain =
        chain(root, intermediate, device, keyDescription(CHALLENGE, TEE, TEE));
    String signature = sign(device, CHALLENGE);
    String publicKey = spki(device.getPublic());

    // Deux comptes liés (clé ACTIVE + code de récupération haché), l'un gelé pour les
    // ré-enrôlements.
    String notFrozen = "acc-tac16-" + UUID.randomUUID();
    String frozen = "acc-tac16-" + UUID.randomUUID();
    for (String acc : List.of(notFrozen, frozen)) {
      String dev = registry.registerPending(acc, publicKey, T0);
      String code = codes.issue(dev, T0);
      assertTrue(enrolment.activate(acc, dev, code));
    }
    trust.notMe(frozen, Alert.REENROL_ATTEMPT, Map.of("alertId", UUID.randomUUID().toString()));

    long[] tNot = new long[N];
    long[] tFro = new long[N];
    Registration expected = new Refused(Refusal.RECOVERY_INVALID);
    for (int i = -WARMUP; i < N; i++) {
      // En alternance, pour que la dérive de la machine (GC, cache) touche les deux cas
      // pareillement.
      long a = System.nanoTime();
      Registration r1 =
          enrolment.register(
              notFrozen, publicKey, validChain, CHALLENGE_B64, signature, "WRONG1", CALLER);
      long b = System.nanoTime();
      Registration r2 =
          enrolment.register(
              frozen, publicKey, validChain, CHALLENGE_B64, signature, "WRONG1", CALLER);
      long c = System.nanoTime();
      assertEquals(expected, r1, "non gelé, code faux");
      assertEquals(expected, r2, "gelé, code faux : même refus");
      if (i >= 0) {
        tNot[i] = (b - a) / 1_000;
        tFro[i] = (c - b) / 1_000;
      }
    }
    Arrays.sort(tNot);
    Arrays.sort(tFro);
    long medNot = percentile(tNot, 0.50), p95Not = percentile(tNot, 0.95);
    long medFro = percentile(tFro, 0.50), p95Fro = percentile(tFro, 0.95);
    int auditFrozen =
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.audit_entry WHERE payload ->> 'accountId' = ?"
                + " AND payload ->> 'event' = 'REENROL_REFUSED'",
            Integer.class,
            frozen);

    System.out.println("TAC16_PBKDF2_ITERATIONS=" + props.iterations());
    System.out.println("TAC16_N=" + N + " WARMUP=" + WARMUP);
    System.out.println(
        "TAC16_NOT_FROZEN_us median="
            + medNot
            + " p95="
            + p95Not
            + " min="
            + tNot[0]
            + " max="
            + tNot[N - 1]);
    System.out.println(
        "TAC16_FROZEN_us     median="
            + medFro
            + " p95="
            + p95Fro
            + " min="
            + tFro[0]
            + " max="
            + tFro[N - 1]);
    System.out.println(
        "TAC16_DELTA_us      median=" + (medFro - medNot) + " p95=" + (p95Fro - p95Not));
    System.out.println(
        "TAC16_AUDIT_REENROL_REFUSED_rows=" + auditFrozen + " (attendu " + (N + WARMUP) + ")");
    assertEquals(N + WARMUP, auditFrozen, "une entrée d'audit par refus gelé");
  }
}
