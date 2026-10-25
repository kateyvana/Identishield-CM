package cm.identishield.core.mobile;

import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.MobileLedger.AccountView;
import cm.identishield.core.mobile.MobileLedger.TransactionView;
import cm.identishield.core.mobile.SessionTokens.LiveSession;
import cm.identishield.core.transfer.OperationType;
import cm.identishield.core.transfer.Proof;
import cm.identishield.core.transfer.TransferResult;
import cm.identishield.core.transfer.TransferService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Routes du quotidien de /mobile/v1 derrière une session vivante (carte B6 ; ISC-MOB-SPEC §7). Le
 * cœur arbitre, le PDP énonce : le nonce d'une opération signée vient du PDP (ES-10), la signature
 * d'une annulation est vérifiée par le PDP avec la clé ACTIVE de l'appareil de la session (ES-20),
 * le cœur n'applique que le verdict. Le compte d'une session est celui que le jeton porte : aucune
 * route ne prend d'identifiant de compte en entrée.
 */
// @implements EF-04
// @implements ES-10
@Component
public class MobileDaily {

  /**
   * Chaîne canonique d'une annulation (ADR-018, amendement M-07) :
   * accountId|CANCEL|transferId|nonce.
   */
  static final String CANCEL = "CANCEL";

  /** État du PDP pour un compte sans histoire (TrustStateMachine, A1-5). */
  static final String TRUSTED = "TRUSTED";

  /** Canal du contrat /evaluate pour l'application. */
  static final String CHANNEL_APP = "APP";

  private final MobileLedger ledger;
  private final DeviceClient device;
  private final TransferService transfers;
  private final PinStore pins;
  private final PinHasher hasher;
  private final PinAttempts attempts;
  private final Alerts alerts;
  private final SecurityAlerts securityAlerts;
  private final Clock clock;

  public MobileDaily(
      MobileLedger ledger,
      DeviceClient device,
      TransferService transfers,
      PinStore pins,
      PinHasher hasher,
      PinAttempts attempts,
      Alerts alerts,
      SecurityAlerts securityAlerts,
      Clock clock) {
    this.ledger = ledger;
    this.device = device;
    this.transfers = transfers;
    this.pins = pins;
    this.hasher = hasher;
    this.attempts = attempts;
    this.alerts = alerts;
    this.securityAlerts = securityAlerts;
    this.clock = clock;
  }

  /**
   * POST /transfers (flux C) : le PIN d'abord, compté par origine (P-11, jamais le compte) ; le
   * bénéficiaire et le solde ensuite (404, 409 : sans appeler le PDP) ; puis UNE opération signée
   * passée à /evaluate avec {@code proofType=SIGNATURE} — jamais NONE depuis la route mobile, le
   * PDP accepterait une opération APP sans preuve. Signature absente : {@code Proof(deviceId, null,
   * nonce)}, le PDP rend SIGNATURE_INVALID et l'audit s'écrit. La réponse ne lit que {@code
   * decision} et {@code vetoApplied}, chaînes du PDP posées telles quelles par le cœur ; jamais
   * {@code detail}.
   */
  // @implements EF-04
  // @implements ES-02
  public Answer transfer(
      LiveSession s,
      long amountXaf,
      String beneficiaryMsisdn,
      String nonce,
      String signature,
      String pin,
      String origin) {
    Optional<Long> locked = attempts.lockedFor(origin);
    if (locked.isPresent()) {
      return Answer.throttled(locked.get());
    }
    Optional<AccountView> account = ledger.find(s.accountId());
    if (account.isEmpty()) {
      return Answer.refused(401, "CREDENTIALS_INVALID");
    }
    Optional<PinStore.AccountPin> stored = pins.find(account.get().msisdn());
    if (!hasher.matches(pin, stored.map(PinStore.AccountPin::pin).orElse(null))) {
      attempts.failed(origin);
      return Answer.refused(401, "CREDENTIALS_INVALID");
    }
    attempts.succeeded(origin);
    if (ledger.findByMsisdn(beneficiaryMsisdn).isEmpty()) {
      return Answer.refused(404, "REQUEST_INVALID");
    }
    if (account.get().balanceXaf() < amountXaf) {
      return Answer.refused(409, "INSUFFICIENT_FUNDS"); // le cœur refuse sans appeler le PDP
    }
    TransferResult r =
        transfers.operate(
            account.get().msisdn(),
            OperationType.P2P,
            amountXaf,
            beneficiaryMsisdn,
            CHANNEL_APP,
            new Proof(s.deviceId(), signature, nonce));
    return outcome(s, amountXaf, r);
  }

  /**
   * La réponse du contrat, décidée sur l'état de l'issue et les chaînes du PDP, jamais sur un
   * texte.
   */
  private Answer outcome(LiveSession s, long amountXaf, TransferResult r) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transferId", r.decisionId());
    switch (r.state()) {
      case EXECUTED, EXECUTED_DEGRADED -> {
        body.put("decision", "ALLOW");
        body.put("status", "EXECUTED");
      }
      case DEFERRED -> {
        body.put("decision", "STEP_UP");
        body.put("status", "DEFERRED");
        body.put("reason", "DECISION_DEFERRED");
        securityAlerts.transferDeferred(s.accountId(), amountXaf); // flux F, informative
        ledger.deferred(s.accountId()).stream()
            .filter(d -> d.transferId().equals(r.decisionId()))
            .findFirst()
            .ifPresent(d -> body.put("executeAt", d.executeAt().toString()));
      }
      case REJECTED -> {
        if ("ACCOUNT_FROZEN".equals(r.vetoApplied())) {
          return Answer.refused(403, "ACCOUNT_FROZEN");
        }
        if ("ALLOW".equals(r.decision())) {
          // Décidée ALLOW mais refusée au commit : seul le débit peut l'avoir refusée.
          return Answer.refused(409, "INSUFFICIENT_FUNDS");
        }
        boolean frozen = "FREEZE".equals(r.decision());
        if (frozen) {
          securityAlerts.transferFrozen(s.accountId()); // flux F, informative
        }
        body.put("decision", frozen ? "FREEZE" : "REJECTED");
        body.put("status", frozen ? "FROZEN" : "REJECTED");
        body.put("reason", frozen ? "DECISION_FROZEN" : "DECISION_REJECTED");
      }
      case CANCELLED -> { // STEP_UP résolu par CANCEL : USSD seulement, jamais depuis l'application
        body.put("decision", "STEP_UP");
        body.put("status", "REJECTED");
        body.put("reason", "DECISION_REJECTED");
      }
    }
    body.put("balanceXaf", ledger.find(s.accountId()).map(AccountView::balanceXaf).orElse(0L));
    return Answer.ok(body);
  }

  /** POST /nonce : un nonce du PDP pour le compte de la session, à usage unique, NONCE_TTL. */
  public Answer nonce(LiveSession s) {
    Reply r = device.nonceForAccount(s.accountId().toString());
    if (r.status() != 200 || r.text("nonce") == null) {
      return Answer.unavailable();
    }
    return Answer.ok(Map.of("nonce", r.text("nonce")));
  }

  /**
   * GET /account : solde, nom, état de confiance et opérations différées. L'état vient du PDP (GET
   * /trust/{accountId}, décision du superviseur technique du 2026-10-24) : le PDP énonce, le cœur
   * recopie sans interpréter. Compte que le PDP n'a jamais vu (404) : TRUSTED, l'état que le PDP
   * donne lui-même à un compte sans histoire ({@code TrustStateMachine}, A1-5). Les opérations
   * différées échues s'exécutent d'abord (ADR-016-A), le solde est lu après.
   */
  public Answer account(LiveSession s) {
    Optional<AccountView> found = ledger.find(s.accountId());
    if (found.isEmpty()) {
      return Answer.refused(401, "CREDENTIALS_INVALID");
    }
    transfers.settleDeferred(found.get().msisdn());
    Reply trust = device.trust(s.accountId().toString());
    String state;
    if (trust.status() == 200 && trust.text("state") != null) {
      state = trust.text("state");
    } else if (trust.status() == 404) {
      state = TRUSTED;
    } else {
      return Answer.unavailable();
    }
    AccountView a = ledger.find(s.accountId()).orElse(found.get());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("accountId", a.id().toString());
    body.put("msisdn", a.msisdn());
    body.put("holderName", nameOrEmpty(a.holderName()));
    body.put("balanceXaf", a.balanceXaf());
    body.put("state", state);
    body.put(
        "deferredOperations",
        ledger.deferred(s.accountId()).stream().map(MobileDaily::deferred).toList());
    return Answer.ok(body);
  }

  /**
   * GET /transactions : historique P2P, les plus récentes d'abord. Les opérations différées échues
   * s'exécutent d'abord (ADR-016-A : évaluées à la lecture, par tout appelant qui les consulte).
   */
  public Answer transactions(LiveSession s) {
    Optional<AccountView> account = ledger.find(s.accountId());
    if (account.isEmpty()) {
      return Answer.refused(401, "CREDENTIALS_INVALID");
    }
    transfers.settleDeferred(account.get().msisdn());
    List<Map<String, Object>> items =
        ledger.history(s.accountId(), account.get().msisdn()).stream()
            .map(MobileDaily::transaction)
            .toList();
    return Answer.ok(Map.of("items", items));
  }

  /**
   * GET /beneficiaries/{msisdn}/holder (ED-11) : nom du titulaire et badge « nouveau bénéficiaire
   * ». {@code beneficiaryId} EST le MSISDN : c'est la chaîne que le cœur met dans
   * EvaluateRequest.beneficiaryId, donc celle que le PDP recalcule dans la chaîne signée (ADR-018).
   * Numéro inconnu du système : 404.
   */
  public Answer holder(LiveSession s, String msisdn) {
    Optional<AccountView> target = ledger.findByMsisdn(msisdn);
    if (target.isEmpty()) {
      return Answer.refused(404, "REQUEST_INVALID");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("msisdn", msisdn);
    body.put("beneficiaryId", msisdn);
    body.put("holderName", nameOrEmpty(target.get().holderName()));
    body.put("newBeneficiary", !ledger.everPaid(s.accountId(), msisdn));
    return Answer.ok(body);
  }

  /**
   * POST /transfers/{transferId}/cancel (E-17) : le PDP vérifie la signature de la chaîne canonique
   * par la clé ACTIVE de l'appareil de la session et consomme le nonce ; VALID seulement annule, et
   * seulement une opération DIFFÉRÉE du compte, avant son échéance. Le nonce est consommé même si
   * rien n'est annulable : une preuve ne sert qu'une fois.
   */
  // @implements ES-20
  public Answer cancel(LiveSession s, String transferId, String nonce, String signature) {
    Optional<AccountView> account = ledger.find(s.accountId());
    if (account.isEmpty()) {
      return Answer.refused(401, "CREDENTIALS_INVALID");
    }
    String payload = String.join("|", s.accountId().toString(), CANCEL, transferId, nonce);
    Reply r = device.verify(s.accountId().toString(), s.deviceId(), payload, signature, nonce);
    if (r.status() != 200 || r.text("verdict") == null) {
      return Answer.unavailable();
    }
    return switch (r.text("verdict")) {
      case "VALID" ->
          transfers.cancelDeferred(account.get().msisdn(), transferId)
              ? Answer.ok(Map.of("transferId", transferId, "status", "CANCELLED"))
              : Answer.refused(404, "REQUEST_INVALID");
      case "REVOKED" -> Answer.refused(403, "KEY_REVOKED");
      default -> Answer.refused(403, "DECISION_REJECTED");
    };
  }

  /**
   * GET /alerts : la requête AUTOMATIQUE de l'application (L-B4-2 : toutes les POLL_FOREGROUND sur
   * tout écran au premier plan), qui maintient la session en vie sans compter comme activité. La
   * liste est vide jusqu'à la carte B5 : aucune alerte n'est fabriquée.
   */
  public Answer alerts(LiveSession s) {
    List<Map<String, Object>> items =
        alerts.list(s.accountId()).stream().map(MobileDaily::alert).toList();
    return Answer.ok(Map.of("items", items));
  }

  /** Chaîne canonique d'une réponse d'alerte (ADR-018, amendement M-08). */
  static final String ALERT_ACK = "ALERT_ACK";

  static final String ALERT_NOT_ME = "ALERT_NOT_ME";

  /**
   * POST /alerts/{alertId}/ack (E-24 « C'est moi », ES-27) : signature de {@code
   * accountId|ALERT_ACK|alertId|nonce} vérifiée par le PDP avec la clé ACTIVE de l'appareil de la
   * session (un appareil révoqué reçoit REVOKED : il ne répond jamais, EF-47). L'alerte doit
   * appartenir au compte et attendre encore une réponse (404 sinon). L'état du compte rendu vient
   * du PDP, lu AVANT de clore l'alerte : s'il manque, rien n'est clos (503).
   */
  // @implements ES-27
  public Answer acknowledge(LiveSession s, UUID alertId, String nonce, String signature) {
    Optional<Alerts.AlertView> open = alerts.findOpen(s.accountId(), alertId);
    if (open.isEmpty()) {
      return Answer.refused(404, "REQUEST_INVALID");
    }
    Answer verdict = verifyAlertAnswer(s, ALERT_ACK, alertId, nonce, signature);
    if (verdict != null) {
      return verdict;
    }
    String state = trustState(s);
    if (state == null) {
      return Answer.unavailable();
    }
    alerts.answer(alertId, Alerts.ACKNOWLEDGED, clock.instant());
    return Answer.ok(
        Map.of(
            "alertId", alertId.toString(), "status", Alerts.ACKNOWLEDGED, "accountState", state));
  }

  /**
   * POST /alerts/{alertId}/not-me (E-24, ES-27) : même vérification de signature que « c'est moi »
   * (chaîne {@code accountId|ALERT_NOT_ME|alertId|nonce}), puis l'EFFET — compte FROZEN cause
   * NOT_ME et ré-enrôlements gelés REENROL_FREEZE — est demandé au PDP (machine A1, {@code
   * TrustStateMachine.notMe}) par la route interne écrite sur la carte #48 et livrée par le couloir
   * A (limite L-B5-1 tant qu'elle n'existe pas : 503). 200 ou 409 (déjà gelé NOT_ME : effet déjà en
   * place) ferment l'alerte NOT_ME ; PDP injoignable : 503, l'alerte reste OPEN.
   */
  // @implements ES-27
  public Answer deny(LiveSession s, UUID alertId, String nonce, String signature) {
    Optional<Alerts.AlertView> open = alerts.findOpen(s.accountId(), alertId);
    if (open.isEmpty()) {
      return Answer.refused(404, "REQUEST_INVALID");
    }
    Answer verdict = verifyAlertAnswer(s, ALERT_NOT_ME, alertId, nonce, signature);
    if (verdict != null) {
      return verdict;
    }
    Reply r =
        device.notMe(s.accountId().toString(), pdpAlert(open.get().type()), alertId.toString());
    String state;
    if (r.status() == 200 && r.text("state") != null) {
      state = r.text("state");
    } else if (r.status() == 409) {
      state = trustState(s);
      if (state == null) {
        return Answer.unavailable();
      }
    } else {
      return Answer.unavailable();
    }
    // Deux réponses concurrentes à la même alerte : le PDP a été appelé deux fois (la seconde fois
    // il répond 409 ou reprolonge : l'effet est en place) ; une seule ferme l'alerte, l'autre rend
    // le même résultat sans rien écrire (observation du couloir A sur #104).
    alerts.answer(alertId, Alerts.NOT_ME, clock.instant());
    return Answer.ok(
        Map.of("alertId", alertId.toString(), "status", Alerts.NOT_ME, "accountState", state));
  }

  /**
   * Type d'alerte du cœur → alerte de la machine A1. Seules les deux alertes à réponse peuvent être
   * OPEN (contrainte de core.alert) ; tout autre type ici est un défaut, jamais deviné.
   */
  private static String pdpAlert(String type) {
    return switch (type) {
      case SecurityAlerts.SIM_CHANGED -> "SIM_CHANGE";
      case SecurityAlerts.REENROL_ATTEMPT -> "REENROL_ATTEMPT";
      default -> throw new IllegalStateException("alerte sans réponse possible : " + type);
    };
  }

  /** Vérification PDP de la réponse signée ; nul si VALID, sinon la réponse de refus. */
  private Answer verifyAlertAnswer(
      LiveSession s, String action, UUID alertId, String nonce, String signature) {
    String payload = String.join("|", s.accountId().toString(), action, alertId.toString(), nonce);
    Reply r = device.verify(s.accountId().toString(), s.deviceId(), payload, signature, nonce);
    if (r.status() != 200 || r.text("verdict") == null) {
      return Answer.unavailable();
    }
    return switch (r.text("verdict")) {
      case "VALID" -> null;
      case "REVOKED" -> Answer.refused(403, "KEY_REVOKED");
      default -> Answer.refused(403, "DECISION_REJECTED");
    };
  }

  /**
   * État de confiance du PDP (GET /trust) ; TRUSTED pour un compte jamais vu ; nul si injoignable.
   */
  private String trustState(LiveSession s) {
    Reply trust = device.trust(s.accountId().toString());
    if (trust.status() == 200 && trust.text("state") != null) {
      return trust.text("state");
    }
    return trust.status() == 404 ? TRUSTED : null;
  }

  private static Map<String, Object> alert(Alerts.AlertView a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("alertId", a.id().toString());
    m.put("type", a.type());
    m.put("at", a.at().toString());
    m.put("summary", a.summary());
    m.put("actions", Alerts.OPEN.equals(a.status()) ? List.of("ACK", "NOT_ME") : List.of());
    m.put("status", a.status());
    return m;
  }

  private static Map<String, Object> deferred(MobileLedger.DeferredView d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("transferId", d.transferId());
    m.put("amountXaf", d.amountXaf());
    m.put("beneficiaryMsisdn", d.beneficiaryMsisdn());
    m.put("executeAt", d.executeAt().toString());
    return m;
  }

  private static Map<String, Object> transaction(TransactionView t) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("transferId", t.transferId());
    m.put("direction", t.direction());
    m.put("amountXaf", t.amountXaf());
    m.put("counterpartyMsisdn", t.counterpartyMsisdn());
    if (t.counterpartyName() != null) {
      m.put("counterpartyName", t.counterpartyName());
    }
    m.put("status", t.status());
    m.put("at", t.at().toString());
    return m;
  }

  /**
   * Le contrat exige une chaîne : un compte sans nom enregistré se présente sans nom, pas en 404.
   */
  private static String nameOrEmpty(String holderName) {
    return holderName == null ? "" : holderName;
  }
}
