package cm.identishield.core;

import cm.identishield.core.account.AccountBootstrap;
import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.hlr.HlrService;
import cm.identishield.core.hlr.ImsiChangeEvent;
import cm.identishield.core.notification.Notification;
import cm.identishield.core.notification.NotificationChannel;
import cm.identishield.core.notification.NotificationRefusedException;
import cm.identishield.core.notification.NotificationService;
import cm.identishield.core.otp.OtpEmission;
import cm.identishield.core.otp.OtpService;
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
import java.util.List;
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
  private final HlrService hlr;
  private final OtpService otp;
  private final NotificationService notifications;

  public CoreRunner(
      PdpClient pdp,
      TransferService transfers,
      UssdService ussd,
      AccountBootstrap accountBootstrap,
      AccountRepository accounts,
      HlrService hlr,
      OtpService otp,
      NotificationService notifications) {
    this.pdp = pdp;
    this.transfers = transfers;
    this.ussd = ussd;
    this.accountBootstrap = accountBootstrap;
    this.accounts = accounts;
    this.hlr = hlr;
    this.otp = otp;
    this.notifications = notifications;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    String mode = env("CORE_MODE", "service");
    switch (mode) {
      case "probe" -> probe();
      case "bench" -> bench();
      case "transfer" -> transfer();
      case "ussd" -> ussd();
      case "hlr" -> hlr();
      case "otp" -> otp();
      case "notification" -> notification();
      default -> service();
    }
  }

  /** Rejoue une session USSD scriptee de bout en bout (le fil ISC-SKEL etape 1). */
  private void ussd() {
    accountBootstrap.ensureSeedAccounts();
    pdp.warmup(); // l'etape finale appelle /evaluate
    String from = env("FROM_MSISDN", "677000001");
    String beneficiary = env("BENEFICIARY_MSISDN", "677000002");
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
    String from = env("FROM_MSISDN", "677000001");
    String beneficiary = env("BENEFICIARY_MSISDN", "677000002");
    long amount = Long.parseLong(env("AMOUNT_XAF", "15000")); // montant de demo (non-parametre)
    TransferResult r = transfers.transferP2P(from, beneficiary, amount, "USSD");
    System.out.println("TRANSFER_STATE=" + r.state());
    System.out.println("TRANSFER_DECISION_ID=" + r.decisionId());
    System.out.println(
        "TRANSFER_BALANCE_AFTER="
            + accounts.findByMsisdn(from).map(a -> a.balanceXaf()).orElse(-1L));
    System.exit(0);
  }

  /**
   * Pilote de vérification du HLR (T2-10), déclenché par CORE_MODE=hlr. Ce n'est PAS l'outil agent
   * (T2-11) : c'est un harnais, comme le mode transfer. Amorce un IMSI initial, puis — si
   * HLR_NEW_IMSI est fourni — effectue UNE réattribution paramétrée (agent, motif) et imprime
   * l'événement émis, l'IMSI actif, la taille de l'historique, et la requête /evaluate EXACTE que
   * le cœur émettrait (pour prouver que le motif n'y transite pas, ES-16b). verify_hlr.sh l'invoque
   * en plusieurs temps.
   */
  private void hlr() {
    accountBootstrap.ensureSeedAccounts();
    String msisdn = env("HLR_MSISDN", "677000001");
    hlr.ensureSubscriber(msisdn, env("HLR_INITIAL_IMSI", "624010000000001"));
    String newImsi = env("HLR_NEW_IMSI", "");
    if (!newImsi.isBlank()) {
      ImsiChangeEvent ev =
          hlr.reassign(
              msisdn,
              newImsi,
              env("HLR_AGENT", "agent-kyc-01"),
              env("HLR_REASON", "remplacement SIM"));
      System.out.println("HLR_OLD_IMSI=" + ev.oldImsi());
      System.out.println("HLR_NEW_IMSI=" + ev.newImsi());
      System.out.println("HLR_CHANGED_AT=" + ev.changedAt());
      System.out.println(
          "HLR_EVENT="
              + ev.msisdn()
              + "|"
              + ev.oldImsi()
              + "|"
              + ev.newImsi()
              + "|"
              + ev.changedAt()
              + "|"
              + ev.agentId());
    }
    System.out.println("HLR_CURRENT_IMSI=" + hlr.currentImsi(msisdn).orElse("(aucun)"));
    System.out.println("HLR_HISTORY_COUNT=" + hlr.history(msisdn).size());
    System.out.println(
        "HLR_EVALUATE_REQUEST="
            + transfers.evaluateRequestPreview(
                msisdn, env("BENEFICIARY_MSISDN", "677000002"), 15000, "USSD"));
    System.exit(0);
  }

  /**
   * Pilote de vérification de l'OTP (T2-12), déclenché par CORE_MODE=otp. Harnais (pas un chemin de
   * production ; l'émetteur réel viendra avec l'enrôlement/step-up au mois 4). Amorce l'IMSI, émet
   * un OTP vers l'IMSI courant, puis le vérifie : code correct (accepté, consommé), rejeu (refusé
   * consommé), mauvais code (refusé inconnu). verify_otp.sh l'appelle avant et après une
   * réattribution pour prouver que l'OTP suit l'IMSI courant.
   */
  private void otp() {
    accountBootstrap.ensureSeedAccounts();
    String msisdn = env("OTP_MSISDN", "677000001");
    hlr.ensureSubscriber(msisdn, env("OTP_INITIAL_IMSI", "624010000000001"));
    switch (env("OTP_ACTION", "scenario")) {
      case "issue" -> {
        OtpEmission em = otp.issue(msisdn);
        System.out.println("OTP_CODE=" + em.code());
        System.out.println("OTP_TARGET_IMSI=" + em.targetImsi());
      }
      case "verify" -> System.out.println("OTP_OUTCOME=" + otp.verify(msisdn, env("OTP_CODE", "")));
      default -> {
        // scenario : emet + verifie correct (accepte, consomme) / rejeu / mauvais code, en un run.
        OtpEmission em = otp.issue(msisdn);
        System.out.println("OTP_TARGET_IMSI=" + em.targetImsi());
        System.out.println("OTP_VERIFY_CORRECT=" + otp.verify(msisdn, em.code()));
        System.out.println("OTP_VERIFY_REUSE=" + otp.verify(msisdn, em.code()));
        String wrong = em.code().equals("000000") ? "111111" : "000000";
        System.out.println("OTP_VERIFY_WRONG=" + otp.verify(msisdn, wrong));
      }
    }
    System.exit(0);
  }

  /**
   * Pilote de vérification des notifications (T2-14, EF-09/ENF-17), déclenché par
   * CORE_MODE=notification. Harnais. {@code NOTIF_ACTION=emit} émet une notification (SMS vers
   * l'IMSI courant par défaut) et imprime la cible et le corps — ou, si le contenu viole ENF-17, le
   * MOTIF de refus et son message. {@code NOTIF_ACTION=list} imprime les notifications d'un numéro.
   * verify_notification.sh l'appelle pour prouver la cible, la persistance, et les trois refus.
   */
  private void notification() {
    accountBootstrap.ensureSeedAccounts();
    String msisdn = env("NOTIF_MSISDN", "677000001");
    hlr.ensureSubscriber(msisdn, env("NOTIF_INITIAL_IMSI", "624010000000001"));
    switch (env("NOTIF_ACTION", "emit")) {
      case "list" -> {
        List<Notification> list = notifications.listByMsisdn(msisdn);
        System.out.println("NOTIF_COUNT=" + list.size());
        for (Notification n : list) {
          System.out.println("NOTIF_LINE=" + n.channel() + "|" + n.recipientRef() + "|" + n.type());
        }
      }
      default -> {
        NotificationChannel channel = NotificationChannel.valueOf(env("NOTIF_CHANNEL", "SMS"));
        String event = env("NOTIF_EVENT", "SECURITY_ALERT");
        String text = env("NOTIF_TEXT", "Rendez-vous en agence pour toute vérification.");
        try {
          Notification n = notifications.emit(msisdn, channel, event, text);
          System.out.println("NOTIF_ID=" + n.id());
          System.out.println("NOTIF_TARGET=" + n.recipientRef());
          System.out.println("NOTIF_BODY=" + n.body());
        } catch (NotificationRefusedException e) {
          System.out.println("NOTIF_REFUSED=" + e.reason());
          System.out.println("NOTIF_MESSAGE=" + e.getMessage());
        }
      }
    }
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

  private void service() {
    accountBootstrap.ensureSeedAccounts(); // le coeur en marche a des comptes
    pdp.warmup(); // etablit la connexion persistante : 1re decision reelle deja chaude
    // Le serveur web (GET /flux pour la console) garde le coeur en vie : pas d'attente.
    log.info("coeur pret (mode service). /flux expose pour la console ; client PDP chauffe.");
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
