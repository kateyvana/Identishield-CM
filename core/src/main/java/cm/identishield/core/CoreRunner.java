package cm.identishield.core;

import cm.identishield.core.account.AccountBootstrap;
import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.pdp.PdpClient;
import cm.identishield.core.pdp.ProbeResult;
import cm.identishield.core.time.MonotonicTimer;
import cm.identishield.core.transfer.TransferResult;
import cm.identishield.core.transfer.TransferService;
import cm.identishield.core.ussd.UssdResponse;
import cm.identishield.core.ussd.UssdService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Point d'entree operationnel du coeur. Trois modes, choisis par la variable d'environnement
 * CORE_MODE :
 *
 * <ul>
 *   <li><b>service</b> (defaut) : le coeur reste en vie (il hebergera l'USSD et le flux
 *       transactionnel des taches suivantes).
 *   <li><b>probe</b> : sonde CORE_TARGET et imprime le motif de refus (4a/4b).
 *   <li><b>bench</b> : mesure la latence mTLS persistante vs sans-TLS (rendu).
 * </ul>
 *
 * <p>Les temps du bench sont pris avec {@link System#nanoTime()} : c'est un minuteur monotone
 * d'INSTRUMENTATION, pas l'horloge systeme d'une regle metier (ENF-11 vise les regles, pas la
 * mesure de performance).
 */
@Component
public class CoreRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CoreRunner.class);

  private final PdpClient pdp;
  private final TransferService transfers;
  private final UssdService ussd;
  private final AccountBootstrap accountBootstrap;
  private final AccountRepository accounts;

  public CoreRunner(
      PdpClient pdp,
      TransferService transfers,
      UssdService ussd,
      AccountBootstrap accountBootstrap,
      AccountRepository accounts) {
    this.pdp = pdp;
    this.transfers = transfers;
    this.ussd = ussd;
    this.accountBootstrap = accountBootstrap;
    this.accounts = accounts;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    String mode = env("CORE_MODE", "service");
    switch (mode) {
      case "probe" -> probe();
      case "bench" -> bench();
      case "transfer" -> transfer();
      case "ussd" -> ussd();
      default -> service();
    }
  }

  /** Rejoue une session USSD scriptee de bout en bout (le fil ISC-SKEL etape 1). */
  private void ussd() {
    accountBootstrap.ensureSeedAccounts();
    pdp.warmup(); // l'etape finale appelle /evaluate
    String from = env("FROM_MSISDN", "699000001");
    String beneficiary = env("BENEFICIARY_MSISDN", "699000002");
    String amount = env("AMOUNT_XAF", "15000");

    UssdService.Interaction dial = ussd.dial(from);
    System.out.println("USSD> " + dial.response().message());
    reply(dial.sessionId(), amount);
    reply(dial.sessionId(), beneficiary);
    UssdResponse fin = replyEcho(dial.sessionId(), "1");
    System.out.println("USSD_FINAL=" + fin.message());
    System.exit(0);
  }

  private void reply(java.util.UUID sessionId, String input) {
    System.out.println("USSD< " + input);
    System.out.println("USSD> " + ussd.input(sessionId, input).message());
  }

  private UssdResponse replyEcho(java.util.UUID sessionId, String input) {
    System.out.println("USSD< " + input);
    UssdResponse r = ussd.input(sessionId, input);
    System.out.println("USSD> " + r.message());
    return r;
  }

  /** Un transfert P2P de bout en bout (verification du fil du squelette). */
  private void transfer() {
    accountBootstrap.ensureSeedAccounts();
    pdp.warmup(); // etablit la connexion persistante avant l'appel /evaluate (JVM froide)
    String from = env("FROM_MSISDN", "699000001");
    String beneficiary = env("BENEFICIARY_MSISDN", "699000002");
    long amount = Long.parseLong(env("AMOUNT_XAF", "15000")); // montant de demo (non-parametre)
    TransferResult r = transfers.transferP2P(from, beneficiary, amount, "USSD");
    System.out.println("TRANSFER_STATE=" + r.state());
    System.out.println("TRANSFER_DECISION_ID=" + r.decisionId());
    System.out.println(
        "TRANSFER_BALANCE_AFTER="
            + accounts.findByMsisdn(from).map(a -> a.balanceXaf()).orElse(-1L));
    System.exit(0);
  }

  private void probe() {
    URI target = URI.create(env("CORE_TARGET", "https://pdp:8443/health"));
    pdp.probe(target); // rechauffe la pile TLS (JVM froide) ; resultat ignore
    ProbeResult r = pdp.probe(target);
    System.out.println("PROBE_TARGET=" + target);
    System.out.println("PROBE_ACCEPTED=" + r.isAccepted());
    System.out.println("PROBE_REASON=" + r.reason());
    System.out.println("PROBE_MESSAGE=" + r.rawMessage());
    System.exit(0);
  }

  private void bench() throws Exception {
    int count = Integer.parseInt(env("CORE_BENCH_COUNT", "1000"));
    URI mtls = URI.create(env("CORE_BENCH_MTLS", "https://pdp:8443/health"));
    URI plain = URI.create(env("CORE_BENCH_PLAIN", "http://plain:8080/health"));
    HttpClient plainClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    long[] mtlsNs = measure(count, () -> pdp.getStatus(mtls));
    long[] plainNs = measure(count, () -> plainStatus(plainClient, plain));

    System.out.println("BENCH_COUNT=" + count);
    report("mtls_persistant", mtlsNs);
    report("plain_sans_tls", plainNs);
    double overheadMs = (p(mtlsNs, 95) - p(plainNs, 95)) / 1_000_000.0;
    System.out.printf("BENCH_SURCOUT_P95_MS=%.3f%n", overheadMs);
    System.out.printf("BENCH_SURCOUT_PCT_ENF01=%.3f%n", overheadMs / 500.0 * 100.0);
    System.exit(0);
  }

  private void service() throws InterruptedException {
    accountBootstrap.ensureSeedAccounts(); // le coeur en marche a des comptes
    pdp.warmup(); // etablit la connexion persistante : 1re decision reelle deja chaude
    log.info(
        "coeur pret (mode service). Client PDP initialise, connexions persistantes chauffees.");
    new CountDownLatch(1).await(); // reste en vie ; T2-05+ ajouteront l'USSD et /evaluate
  }

  private interface Call {
    int run() throws Exception;
  }

  private static long[] measure(int count, Call call) throws Exception {
    call.run(); // rechauffe (etablit la connexion persistante)
    long[] ns = new long[count];
    for (int i = 0; i < count; i++) {
      long t0 = MonotonicTimer.nanos();
      call.run();
      ns[i] = MonotonicTimer.nanos() - t0;
    }
    Arrays.sort(ns);
    return ns;
  }

  private static int plainStatus(HttpClient c, URI uri) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(800)).GET().build();
    return c.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static void report(String label, long[] sortedNs) {
    System.out.printf(
        "BENCH_%s_MS p50=%.3f p95=%.3f p99=%.3f%n",
        label, p(sortedNs, 50) / 1e6, p(sortedNs, 95) / 1e6, p(sortedNs, 99) / 1e6);
  }

  private static double p(long[] sortedNs, int pct) {
    int idx = (int) Math.ceil(pct / 100.0 * sortedNs.length) - 1;
    return sortedNs[Math.max(0, Math.min(idx, sortedNs.length - 1))];
  }

  private static String env(String key, String fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }
}
