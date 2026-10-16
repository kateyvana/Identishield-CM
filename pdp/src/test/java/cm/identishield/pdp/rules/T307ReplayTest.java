package cm.identishield.pdp.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.rules.Score.Decision;
import cm.identishield.pdp.rules.StepUpPolicy.Resolution;
import cm.identishield.pdp.trust.ProbationVeto;
import cm.identishield.pdp.trust.ProbationVeto.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Reproduction de la calibration T3-07 par le moteur du PDP : le plan AC-02 gelé (300 cas) est
 * rejoué contre l'historique légitime gelé de chaque victime (dev horizon v1), et les chiffres
 * d'ADR-016 §4 doivent être retrouvés EXACTEMENT. Les empreintes du jeu et du plan sont vérifiées
 * contre leurs manifestes avant tout calcul.
 *
 * <p>Le jeu n'est pas versionné (régénérable à l'octet, ~80 Mo) : sans ISC_DEV_HORIZON (chemin du
 * jeu sans extension), la classe est ignorée ; tools/verify_t3_07_replay.sh le régénère et la
 * lance.
 *
 * <p>Exigences vérifiées :
 *
 * <p>@covers ES-04
 *
 * <p>@covers ES-05a
 *
 * <p>@covers ES-05f
 *
 * <p>@covers AC-02
 */
@EnabledIfEnvironmentVariable(named = "ISC_DEV_HORIZON", matches = ".+")
class T307ReplayTest {

  private static final Path PLAN = Path.of("../data/eval/ac02_plan.csv");
  private static final Path PLAN_MANIFEST = Path.of("../data/eval/ac02_plan.manifest.json");
  private static final Path HORIZON_MANIFEST = Path.of("../data/eval/dev_horizon_v1.manifest.json");

  private final RuleSet rs = TestRuleSet.load();

  private record Case(String victim, Instant at, String beneficiary, long balance) {}

  private static String sha256(Path p) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(p)) {
      byte[] buf = new byte[1 << 16];
      for (int n; (n = in.read(buf)) > 0; ) {
        md.update(buf, 0, n);
      }
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static List<String[]> rows(Path p) throws Exception {
    List<String[]> out = new ArrayList<>();
    try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
      r.readLine(); // en-tête
      for (String line; (line = r.readLine()) != null; ) {
        out.add(line.split(",", -1));
      }
    }
    return out;
  }

  private List<Case> plan() throws Exception {
    // run_id,victim_msisdn,victim_imsi,instant,beneficiary_msisdn,attacker_imsi,balance_xaf
    return rows(PLAN).stream()
        .map(c -> new Case(c[1], Instant.parse(c[3]), c[4], Long.parseLong(c[6])))
        .toList();
  }

  /** Historique de chaque victime, dans l'ordre du temps (vue détecteur du jeu gelé). */
  private Map<String, List<Operation>> histories(Path horizon, Set<String> victims)
      throws Exception {
    // id,user,direction,amount,timestamp,beneficiary,...
    Map<String, List<Operation>> h = new HashMap<>();
    try (BufferedReader r = Files.newBufferedReader(horizon, StandardCharsets.UTF_8)) {
      r.readLine();
      for (String line; (line = r.readLine()) != null; ) {
        String[] c = line.split(",", -1);
        if (victims.contains(c[1])) {
          h.computeIfAbsent(c[1], k -> new ArrayList<>())
              .add(
                  new Operation(
                      c[5], Long.parseLong(c[3]), "OUT".equals(c[2]), Instant.parse(c[4])));
        }
      }
    }
    h.values().forEach(l -> l.sort((a, b) -> a.at().compareTo(b.at())));
    return h;
  }

  private static List<Operation> before(List<Operation> h, Instant t) {
    return h == null ? List.of() : h.stream().filter(o -> o.at().isBefore(t)).toList();
  }

  /** Pourcentage à une décimale, comme ADR-016 §4. */
  private static String pct(long k, long n) {
    return String.format(java.util.Locale.ROOT, "%.1f", 100.0 * k / n);
  }

  /** Intervalle de Wilson à 95 %, à une décimale, comme ADR-016 §4. */
  private static String wilson(long k, long n) {
    double z = 1.96;
    double p = (double) k / n;
    double d = 1 + z * z / n;
    double c = (p + z * z / (2.0 * n)) / d;
    double h = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d;
    return String.format(java.util.Locale.ROOT, "%.1f–%.1f", 100 * (c - h), 100 * (c + h));
  }

  @Test
  void adr016Section4_isReproducedExactly() throws Exception {
    Path horizon = Path.of(System.getenv("ISC_DEV_HORIZON") + ".csv");
    ObjectMapper json = new ObjectMapper();
    JsonNode hm = json.readTree(HORIZON_MANIFEST.toFile());
    assertEquals(
        hm.at("/horizon_hashes_attendus/vue_detecteur/sha256").asText(),
        sha256(horizon),
        "le jeu fourni n'est pas le dev horizon v1 gelé");
    JsonNode pm = json.readTree(PLAN_MANIFEST.toFile());
    assertEquals(pm.at("/plan/sha256").asText(), sha256(PLAN), "le plan n'est pas le plan gelé");

    List<Case> plan = plan();
    Map<String, List<Operation>> hist =
        histories(horizon, plan.stream().map(Case::victim).collect(Collectors.toSet()));
    RuleEngine engine = new RuleEngine(rs);
    ProbationVeto veto = new ProbationVeto(rs);
    StepUpPolicy policy = StepUpPolicy.from(rs);

    Map<Decision, Integer> sansImsi = new EnumMap<>(Decision.class);
    Map<Violation, Integer> avecImsi = new EnumMap<>(Violation.class);
    for (Case c : plan) {
      List<Operation> h = before(hist.get(c.victim()), c.at());
      // L'attaquant vide le solde vers son bénéficiaire, en une sortie (ISC-TM AC-02).
      Operation drain = new Operation(c.beneficiary(), c.balance(), true, c.at());
      sansImsi.merge(engine.score(drain, h).decision(), 1, Integer::sum);
      // Avec le signal IMSI : PROBATION ouverte par l'échange de SIM juste avant le vidage.
      Optional<Violation> v = veto.check(drain, h, c.at(), c.balance());
      avecImsi.merge(v.orElseThrow(), 1, Integer::sum);
    }

    int n = plan.size();
    int freeze = sansImsi.getOrDefault(Decision.FREEZE, 0);
    int stepUp = sansImsi.getOrDefault(Decision.STEP_UP, 0);
    int allow = sansImsi.getOrDefault(Decision.ALLOW, 0);
    // AC-02 se joue sur USSD : un STEP_UP y est résolu selon STEP_UP_POLICY.ussd.
    boolean cancelled = policy.forChannel("USSD") == Resolution.CANCEL;
    int tp = freeze + (cancelled ? stepUp : 0);
    System.out.printf(
        "T3-07 rejoué : n=%d ; avec IMSI %s ; sans IMSI FREEZE=%d STEP_UP=%d ALLOW=%d ; TPR=%d/%d%n",
        n, avecImsi, freeze, stepUp, allow, tp, n);

    assertEquals(300, n);
    // ADR-016 §4, ligne 1 : avec signal IMSI, veto ES-04 (bénéficiaire nouveau en PROBATION) : 100
    // %.
    assertEquals(Map.of(Violation.NEW_BENEFICIARY, 300), avecImsi);
    // Ligne 2 : sans IMSI, USSD = BLOQUER : FREEZE 23,7 % · STEP_UP → CANCELLED 66,3 % · ALLOW 10,0
    // %.
    assertEquals("23.7", pct(freeze, n));
    assertEquals("66.3", pct(stepUp, n));
    assertEquals("10.0", pct(allow, n));
    assertEquals(Resolution.CANCEL, policy.forChannel("USSD"));
    assertEquals("90.0", pct(tp, n));
    assertEquals("86.1–92.9", wilson(tp, n));
    // Ligne 3 : report DEFER (alternative écartée) : seul le FREEZE compte, 23,7 % [19,2–28,8].
    assertEquals("23.7", pct(freeze, n));
    assertEquals("19.2–28.8", wilson(freeze, n));
  }
}
