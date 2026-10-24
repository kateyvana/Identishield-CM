package cm.identishield.core.mobile;

import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.ImsiChangeEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Les événements de sécurité qui deviennent des alertes du téléphone lié (ISC-MOB-SPEC §9.2 ;
 * ES-27, EF-47). Le cœur est la source : changement de SIM (HLR), tentatives de ré-enrôlement
 * échouées au seuil ALERT_REENROL, ré-enrôlement réussi, opération différée ou gelée. Les textes
 * sont fixes et propres (ES-28) : ni lien, ni code, ni demande de saisie. Lever une alerte n'échoue
 * jamais l'opération qui la provoque.
 */
// @implements ES-27
// @implements EF-47
@Component
public class SecurityAlerts {

  private static final Logger log = LoggerFactory.getLogger(SecurityAlerts.class);

  static final String SIM_CHANGED = "SIM_CHANGED";
  static final String REENROL_ATTEMPT = "REENROL_ATTEMPT";
  static final String DEVICE_REBOUND = "DEVICE_REBOUND";
  static final String TRANSFER_DEFERRED = "TRANSFER_DEFERRED";
  static final String TRANSFER_FROZEN = "TRANSFER_FROZEN";

  // Textes d'écran (ES-28). L'heure n'y figure pas : l'alerte porte son instant (`at`), que
  // l'application affiche dans le fuseau du téléphone ; le serveur ne calcule aucune heure locale.
  static final String TEXT_SIM_CHANGED = "La carte SIM de votre numéro a été changée. C'est vous ?";
  static final String TEXT_REENROL_ATTEMPT =
      "Quelqu'un tente de lier un nouveau téléphone à votre compte.";
  static final String TEXT_DEVICE_REBOUND = "Un nouveau téléphone a été lié à votre compte.";
  static final String TEXT_TRANSFER_DEFERRED = "Votre envoi de %d FCFA sera exécuté plus tard.";
  static final String TEXT_TRANSFER_FROZEN = "Une opération a été bloquée pour votre sécurité.";

  private final Alerts alerts;
  private final MobileLedger ledger;
  private final Clock clock;
  private final int reenrolCount;
  private final Duration reenrolWindow;
  // Échecs de ré-enrôlement par compte dans la fenêtre, et comptes déjà alertés : en mémoire, comme
  // PinAttempts ; le comptage durable (console, ES-24) est celui du PDP. Un redémarrage repart à
  // zéro.
  private final Map<UUID, Deque<Instant>> failures = new ConcurrentHashMap<>();
  private final Map<UUID, Boolean> alerted = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public SecurityAlerts(Alerts alerts, MobileLedger ledger, Clock clock, RuleSet ruleSet) {
    this(alerts, ledger, clock, ruleSet.alertReenrolCount(), ruleSet.alertReenrolWindow());
  }

  SecurityAlerts(
      Alerts alerts, MobileLedger ledger, Clock clock, int reenrolCount, Duration reenrolWindow) {
    this.alerts = alerts;
    this.ledger = ledger;
    this.clock = clock;
    this.reenrolCount = reenrolCount;
    this.reenrolWindow = reenrolWindow;
  }

  /**
   * Changement de SIM sur le numéro (HLR, EF-08) : alerte à réponse au téléphone lié. Écoutée dans
   * la transaction de la réattribution ; une alerte qui ne peut pas s'écrire ne défait jamais la
   * réattribution de l'opérateur.
   */
  @EventListener
  public void onImsiChanged(ImsiChangeEvent event) {
    try {
      ledger
          .findByMsisdn(event.msisdn())
          .ifPresent(a -> raise(a.id(), SIM_CHANGED, TEXT_SIM_CHANGED, true));
    } catch (RuntimeException e) {
      log.warn("alerte SIM_CHANGED non levée pour {} : {}", event.msisdn(), e.getMessage());
    }
  }

  /**
   * Un ré-enrôlement a échoué au code (PDP : RECOVERY_INVALID). Au ALERT_REENROL.count-ième échec
   * dans ALERT_REENROL.window, une seule alerte à réponse, jusqu'au prochain succès. Jamais aucune
   * restriction du compte (ES-24).
   */
  public void reenrolFailed(UUID accountId) {
    Instant now = clock.instant();
    Deque<Instant> window = failures.computeIfAbsent(accountId, k -> new ArrayDeque<>());
    boolean reached;
    synchronized (window) {
      window.addLast(now);
      while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(reenrolWindow))) {
        window.pollFirst();
      }
      reached = window.size() >= reenrolCount;
    }
    if (reached && alerted.putIfAbsent(accountId, Boolean.TRUE) == null) {
      raise(accountId, REENROL_ATTEMPT, TEXT_REENROL_ATTEMPT, true);
    }
  }

  /**
   * Ré-enrôlement réussi : le compteur repart ; si une clé ACTIVE a été remplacée ({@code
   * rebound}), l'alerte informative DEVICE_REBOUND est levée (EF-47) — l'ancien téléphone, révoqué,
   * ne peut plus signer de réponse : le PDP lui répond REVOKED.
   */
  public void reenrolSucceeded(UUID accountId, boolean rebound) {
    failures.remove(accountId);
    alerted.remove(accountId);
    if (rebound) {
      raise(accountId, DEVICE_REBOUND, TEXT_DEVICE_REBOUND, false);
    }
  }

  /**
   * STEP_UP résolu par DEFER : informative, l'annulation passe par l'opération elle-même (E-17).
   */
  public void transferDeferred(UUID accountId, long amountXaf) {
    raise(accountId, TRANSFER_DEFERRED, String.format(TEXT_TRANSFER_DEFERRED, amountXaf), false);
  }

  /** FREEZE décidé par le PDP : informative. */
  public void transferFrozen(UUID accountId) {
    raise(accountId, TRANSFER_FROZEN, TEXT_TRANSFER_FROZEN, false);
  }

  private void raise(UUID accountId, String type, String summary, boolean actionable) {
    try {
      alerts.raise(accountId, type, summary, actionable, clock.instant());
    } catch (RuntimeException e) {
      log.warn("alerte {} non levée pour {} : {}", type, accountId, e.getMessage());
    }
  }
}
