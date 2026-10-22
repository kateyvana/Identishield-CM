package cm.identishield.pdp.web;

import static org.mockito.Mockito.mock;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.audit.AuthorizedHistory;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.rules.RuleEngine;
import cm.identishield.pdp.signature.NonceStore;
import cm.identishield.pdp.signature.SignatureVerifier;
import cm.identishield.pdp.time.VirtualClock;
import cm.identishield.pdp.trust.DecisionEnforcer;
import cm.identishield.pdp.trust.ProbationVeto;
import cm.identishield.pdp.trust.TrustStateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Banc de latence de la chaîne /evaluate réelle (contrôleur, journal, machine à états, moteur,
 * veto, gel) sur vraie base. Hors build : tools/bench_evaluate/run.sh le copie dans l'arbre de
 * chaque commit comparé. Méthode et réserves : tools/bench_evaluate/README.md.
 */
@EnabledIfEnvironmentVariable(named = "ISC_BENCH_OUT", matches = ".+")
class EvaluateLatencyBench {

  private static final Instant T0 = Instant.parse("2026-10-21T10:00:00Z");

  private record Case(String runId, String msisdn, String victimImsi, String beneficiary,
      String attackerImsi, long balance) {}

  private record Sample(String phase, String kind, String decision, long micros, long latencyMs) {}

  @Test
  void bench() throws Exception {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(System.getenv("ISC_TEST_PG_URL"));
    hc.setUsername(System.getenv("ISC_TEST_PG_USER"));
    hc.setPassword(System.getenv("ISC_TEST_PG_PASSWORD"));
    hc.setMaximumPoolSize(10); // défaut Spring Boot, comme le PDP
    try (HikariDataSource ds = new HikariDataSource(hc)) {
      JdbcTemplate jdbc = new JdbcTemplate(ds);
      DataSourceTransactionManager transactions = new DataSourceTransactionManager(ds);
      RuleSet rs = TestRuleSet.load();
      VirtualClock clock = new VirtualClock(T0);
      AuditLog audit = new AuditLog(jdbc, new ObjectMapper());
      TrustStateMachine trust = new TrustStateMachine(jdbc, transactions, audit, clock, rs);
      Map<Class<?>, Object> parts = new HashMap<>();
      parts.put(AuditLog.class, audit);
      parts.put(Clock.class, clock);
      parts.put(RuleSet.class, rs);
      parts.put(SignatureVerifier.class,
          new SignatureVerifier(mock(DeviceRegistry.class), mock(NonceStore.class)));
      parts.put(TrustStateMachine.class, trust);
      parts.put(AuthorizedHistory.class, new AuthorizedHistory(jdbc));
      parts.put(RuleEngine.class, new RuleEngine(rs));
      parts.put(ProbationVeto.class, new ProbationVeto(rs));
      parts.put(DecisionEnforcer.class, new DecisionEnforcer(trust));
      parts.put(PlatformTransactionManager.class, transactions);
      Constructor<?> ctor = EvaluateController.class.getConstructors()[0];
      Object[] args = new Object[ctor.getParameterCount()];
      for (int i = 0; i < args.length; i++) {
        Class<?> t = ctor.getParameterTypes()[i];
        args[i] = parts.entrySet().stream().filter(e -> t.isAssignableFrom(e.getKey()))
            .findFirst().orElseThrow(() -> new IllegalStateException("type " + t)).getValue();
      }
      EvaluateController c = (EvaluateController) ctor.newInstance(args);

      List<Case> plan = new ArrayList<>();
      try (BufferedReader r = Files.newBufferedReader(Path.of("../data/eval/ac02_plan.csv"),
          StandardCharsets.UTF_8)) {
        r.readLine();
        for (String l; (l = r.readLine()) != null; ) {
          String[] f = l.split(",", -1);
          plan.add(new Case(f[0], f[1], f[2], f[4], f[5], Long.parseLong(f[6])));
        }
      }
      String tag = System.getenv("ISC_BENCH_TAG");
      List<Sample> out = Collections.synchronizedList(new ArrayList<>());

      // Rodage jeté (observation 2026-09-15 : la première mesure à froid n'est pas représentative).
      for (int i = 0; i < 60; i++) {
        run(c, "warmup", tag, plan.get(i % plan.size()), i % 2 == 0, out);
      }
      out.clear();

      // 1. Séquentiel : 300 FREEZE (plan AC-02 avec IMSI) + 300 ALLOW, alternés.
      for (int i = 0; i < plan.size(); i++) {
        run(c, "seq", tag, plan.get(i), true, out);
        run(c, "seq", tag, plan.get(i), false, out);
      }
      // 2. Rythme SQ-30 : 50 requêtes/s, moitié FREEZE moitié ALLOW, 600 requêtes (12 s).
      paced(c, tag, plan, out);
      // 3. Saturation : 16 fils en boucle fermée, 600 requêtes.
      ExecutorService pool = Executors.newFixedThreadPool(16);
      List<Future<?>> fs = new ArrayList<>();
      for (int i = 0; i < plan.size(); i++) {
        Case k = plan.get(i);
        fs.add(pool.submit(() -> run(c, "sat16", tag, k, true, out)));
        fs.add(pool.submit(() -> run(c, "sat16", tag, k, false, out)));
      }
      for (Future<?> f : fs) {
        f.get();
      }
      pool.shutdown();

      StringBuilder sb = new StringBuilder("phase,kind,decision,micros,latencyMs\n");
      synchronized (out) {
        for (Sample s : out) {
          sb.append(s.phase).append(',').append(s.kind).append(',').append(s.decision).append(',')
              .append(s.micros).append(',').append(s.latencyMs).append('\n');
        }
      }
      Files.writeString(Path.of(System.getenv("ISC_BENCH_OUT")), sb.toString());
    }
  }

  private static void paced(EvaluateController c, String tag, List<Case> plan, List<Sample> out)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(64);
    List<Future<?>> fs = new ArrayList<>();
    long start = System.nanoTime();
    long periodNanos = Duration.ofMillis(20).toNanos(); // 50/s
    for (int i = 0; i < 2 * plan.size(); i++) {
      long due = start + i * periodNanos;
      long wait = due - System.nanoTime();
      if (wait > 0) {
        TimeUnit.NANOSECONDS.sleep(wait);
      }
      Case k = plan.get(i / 2);
      boolean freeze = i % 2 == 0;
      fs.add(pool.submit(() -> run(c, "sq30", tag, k, freeze, out)));
    }
    for (Future<?> f : fs) {
      f.get();
    }
    pool.shutdown();
  }

  private static void run(EvaluateController c, String phase, String tag, Case k, boolean freeze,
      List<Sample> out) {
    String acc = "bench-" + tag + "-" + phase + "-" + k.runId + (freeze ? "-f-" : "-a-")
        + UUID.randomUUID();
    EvaluateRequest req = new EvaluateRequest(
        UUID.randomUUID().toString(), acc, k.msisdn,
        freeze ? k.attackerImsi : k.victimImsi, "USSD", "P2P",
        freeze ? k.balance : 1_000, k.beneficiary, T0.toString(),
        null, null, null, null, "NONE", k.balance,
        freeze ? T0.minus(Duration.ofHours(1)).toString() : null);
    long t = System.nanoTime();
    EvaluateResponse r = c.evaluate(req);
    long micros = (System.nanoTime() - t) / 1_000;
    String expected = freeze ? "FREEZE" : "ALLOW";
    if (!expected.equals(r.decision())) {
      throw new IllegalStateException(phase + " " + k.runId + " : " + r.decision());
    }
    out.add(new Sample(phase, freeze ? "FREEZE" : "ALLOW", r.decision(), micros, r.latencyMs()));
  }
}
