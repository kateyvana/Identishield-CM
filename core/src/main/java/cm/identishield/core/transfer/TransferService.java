package cm.identishield.core.transfer;

import cm.identishield.core.account.Account;
import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.HlrReassignment;
import cm.identishield.core.hlr.HlrService;
import cm.identishield.core.mode.LayerMode;
import cm.identishield.core.notification.NotificationChannel;
import cm.identishield.core.notification.NotificationService;
import cm.identishield.core.pdp.EvaluateOutcome;
import cm.identishield.core.pdp.FailClosedPolicy;
import cm.identishield.core.pdp.Outcome;
import cm.identishield.core.pdp.PdpClient;
import cm.identishield.core.transaction.PendingTransaction;
import cm.identishield.core.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Opérations du cœur (EF-04) avec appel au PDP AVANT tout commit (EF-05). Le fil du squelette
 * (ISC-SKEL §2), identique pour les six types (P2P + cash-in, cash-out, marchand, facture, crédit)
 * : créer la transaction PENDING_DECISION (+ decisionId généré en base) -> POST /evaluate en mTLS
 * -> appliquer la décision (ALLOW => mouvement de solde + EXECUTED). Si le PDP est indisponible,
 * politique fail-closed (ES-11) sur les SORTIES uniquement : refus au-dessus de CAP_FAILCLOSED,
 * mode dégradé en dessous ; une entrée (CASH_IN) passe toujours en mode dégradé, quel que soit le
 * montant (ES-11 ne vise que les sorties — décision 15/09, correction SRS à venir).
 *
 * <p>Aucune règle ni machine à états de confiance au mois 2. currentImsi est résolu depuis le HLR
 * (T2-10). decisionId généré en base (gen_random_uuid), jamais UUID.randomUUID.
 */
// @implements EF-04
// @implements EF-05
@Service
public class TransferService {

  private static final Logger log = LoggerFactory.getLogger(TransferService.class);
  // Carte #57 (A5-00) : plus de PLACEHOLDER_IMSI. Depuis A4, le PDP épingle currentImsi comme IMSI
  // de référence du compte ; envoyer un IMSI factice (fallback silencieux) revenait à faire
  // tomber un utilisateur légitime en PROBATION 72 h dès que son HLR serait renseigné après coup.
  // L'opération est désormais refusée en amont si le HLR ne connaît aucune SIM pour le numéro.

  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final PdpClient pdp;
  private final RuleSet ruleSet;
  private final Clock clock;
  private final ObjectMapper json;
  private final HlrService hlr;
  private final NotificationService notifications;
  private final LayerMode layerMode;

  public TransferService(
      AccountRepository accounts,
      TransactionRepository transactions,
      PdpClient pdp,
      RuleSet ruleSet,
      Clock clock,
      ObjectMapper json,
      HlrService hlr,
      NotificationService notifications,
      LayerMode layerMode) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.pdp = pdp;
    this.ruleSet = ruleSet;
    this.clock = clock;
    this.json = json;
    this.hlr = hlr;
    this.notifications = notifications;
    this.layerMode = layerMode;
  }

  /**
   * Transfert P2P (EF-04) : cas particulier de {@link #operate} qui débite l'initiateur et crédite
   * le bénéficiaire intra-système. Signature conservée pour les appelants existants (USSD,
   * console).
   */
  public TransferResult transferP2P(
      String fromMsisdn, String beneficiaryMsisdn, long amountXaf, String channel) {
    return operate(fromMsisdn, OperationType.P2P, amountXaf, beneficiaryMsisdn, channel);
  }

  /**
   * Exécute les opérations différées échues du compte (ADR-016-A : exécution à l'échéance sauf
   * annulation). Évaluée à la lecture (ADR-019), sans ordonnanceur : à chaque nouvelle opération du
   * compte, et par tout appelant qui consulte ses opérations différées (route mobile, couloir B).
   */
  public List<TransferResult> settleDeferred(String msisdn) {
    return settle(account(msisdn), clock.instant());
  }

  /**
   * Annule une opération différée du compte, seulement avant son échéance (E-17). Faux si elle
   * n'est plus annulable (échue, déjà exécutée ou annulée) ou n'appartient pas au compte.
   */
  public boolean cancelDeferred(String msisdn, String decisionId) {
    return transactions.cancelDeferred(account(msisdn).id(), decisionId, clock.instant());
  }

  private Account account(String msisdn) {
    return accounts
        .findByMsisdn(msisdn)
        .orElseThrow(() -> new IllegalArgumentException("compte inconnu : " + msisdn));
  }

  private List<TransferResult> settle(Account account, Instant now) {
    List<TransferResult> done = new ArrayList<>();
    for (TransactionRepository.DeferredTransaction d :
        transactions.findDueDeferred(account.id(), now)) {
      if (transactions.claimDueDeferred(d.id(), now)) {
        done.add(
            commit(
                new PendingTransaction(d.id(), d.decisionId()),
                account,
                OperationType.valueOf(d.type()),
                d.beneficiaryId(),
                d.amountXaf(),
                TransferResult.State.EXECUTED));
      }
    }
    return done;
  }

  /** STEP_UP (ES-06) : USSD -> CANCELLED, application -> DEFERRED jusqu'à now + DEFER. */
  private TransferResult resolveStepUp(PendingTransaction tx, String channel, Instant now) {
    if (new StepUpResolver(ruleSet).forChannel(channel) == StepUpResolver.Resolution.CANCEL) {
      transactions.markCancelled(tx.id());
      return TransferResult.cancelled(tx.decisionId(), "STEP_UP annulé (STEP_UP_POLICY)");
    }
    Instant executeAt = now.plus(ruleSet.defer());
    transactions.markDeferred(tx.id(), executeAt);
    return TransferResult.deferred(tx.decisionId(), executeAt);
  }

  /**
   * Exécute une opération de N'IMPORTE QUEL type (EF-04) en passant par le PDP AVANT tout commit
   * (EF-05) — chemin unique pour les six types. Créer la transaction PENDING_DECISION (+ decisionId
   * généré en base) -> POST /evaluate en mTLS -> appliquer la décision. ALLOW => commit (débit ou
   * crédit selon la direction) + EXECUTED ; refus explicite => REJECTED. PDP indisponible =>
   * fail-closed (ES-11) sur les SORTIES : refus au-dessus de CAP_FAILCLOSED, dégradé en dessous ;
   * une entrée (CASH_IN) passe toujours en dégradé. Aucun type ne modifie un solde avant d'avoir
   * appelé /evaluate.
   *
   * <p>{@code counterparty} remplit le champ {@code beneficiaryId} requis par le contrat : le
   * bénéficiaire (P2P) ou l'identifiant de la contrepartie externe simulée (agent, marchand,
   * facturier, opérateur) pour les autres types.
   */
  public TransferResult operate(
      String msisdn, OperationType type, long amountXaf, String counterparty, String channel) {
    Account initiator = account(msisdn);
    Instant now = clock.instant();
    // Les opérations différées échues du compte s'exécutent avant toute nouvelle opération (A3).
    settle(initiator, now);
    PendingTransaction tx =
        transactions.createPending(
            initiator.id(), type.name(), amountXaf, counterparty, channel, now);

    // Carte #57 (A5-00) : refus de toute opération si le HLR ne connaît aucune SIM pour le numéro
    // de l'initiateur, dans les deux modes (sans couche et protégé). L'HLR est l'opérateur, pas la
    // couche de sécurité : un numéro sans SIM ne peut pas transiger. Sans ce refus, le cœur
    // enverrait un IMSI factice au PDP, qui l'épinglerait comme IMSI de référence (A4) : un
    // utilisateur légitime tomberait ensuite en PROBATION 72 h dès que son HLR serait renseigné,
    // sans aucun SIM-swap.
    if (hlr.currentImsi(msisdn).isEmpty()) {
      transactions.markRejected(tx.id());
      log.warn("A5-00 : {} de {} XAF REFUSE : HLR sans IMSI pour {}", type, amountXaf, msisdn);
      return TransferResult.rejected(
          tx.decisionId(), "A5-00 : numéro sans SIM connue de l'opérateur");
    }

    if (layerMode.isLayerless()) {
      // Mode sans couche (EF-07) : la couche de sécurité est DÉSACTIVÉE — aucun appel /evaluate.
      // C'est l'état « avant » de la démonstration, où une sortie sensible passe sans être décidée.
      log.warn(
          "MODE SANS COUCHE : {} de {} XAF exécuté SANS la couche de sécurité (démonstration)",
          type,
          amountXaf);
      return commit(tx, initiator, type, counterparty, amountXaf, TransferResult.State.EXECUTED);
    }

    EvaluateOutcome outcome =
        pdp.evaluate(
            buildRequest(tx.decisionId(), initiator, type, counterparty, amountXaf, channel, now));

    if (outcome.available()) {
      if (outcome.isAllow()) {
        return commit(tx, initiator, type, counterparty, amountXaf, TransferResult.State.EXECUTED);
      }
      if (outcome.isStepUp()) {
        return resolveStepUp(tx, channel, now);
      }
      // FREEZE (le compte est gelé par le PDP, A1) ou REJECTED : la transaction est bloquée.
      transactions.markRejected(tx.id());
      return TransferResult.rejected(tx.decisionId(), "decision=" + outcome.decision());
    }

    // PDP indisponible : le fail-closed (ES-11) ne protège que les SORTIES. Une entrée (CASH_IN)
    // passe TOUJOURS, en mode dégradé et journalisée, quel que soit le montant : refuser un dépôt
    // quand le PDP est indisponible ne protège personne — ça bloque un client légitime devant un
    // agent sans gêner l'attaquant (même défaut que le verrouillage écarté en ADR-030). ES-11 ne
    // vise que les sorties. Décision du propriétaire (15/09), correction ES-11 à remonter au SRS :
    // voir docs/03-revues/2026-09-15-correction-es-11-fail-closed-sorties-seulement.md.
    if (type.isOutflow()) {
      Outcome policy = new FailClosedPolicy(ruleSet.capFailClosedXaf()).onPdpUnavailable(amountXaf);
      if (policy == Outcome.REFUSED_FAIL_CLOSED) {
        transactions.markRejected(tx.id());
        log.warn(
            "PDP indisponible : {} de {} XAF REFUSE (> CAP_FAILCLOSED), fail-closed",
            type,
            amountXaf);
        return TransferResult.rejected(
            tx.decisionId(), "fail-closed : PDP indisponible, montant > CAP_FAILCLOSED");
      }
    }
    log.warn("PDP indisponible : {} de {} XAF appliqué en mode DEGRADE", type, amountXaf);
    return commit(
        tx, initiator, type, counterparty, amountXaf, TransferResult.State.EXECUTED_DEGRADED);
  }

  /**
   * Applique le mouvement de solde APRÈS décision : une sortie débite l'initiateur (et, pour le
   * P2P, crédite le bénéficiaire intra-système s'il existe) ; une entrée (CASH_IN) crédite
   * l'initiateur. N'est jamais atteint sans un passage préalable par /evaluate.
   */
  private TransferResult commit(
      PendingTransaction tx,
      Account initiator,
      OperationType type,
      String counterparty,
      long amountXaf,
      TransferResult.State state) {
    if (type.isOutflow()) {
      if (!accounts.debit(initiator.id(), amountXaf)) {
        transactions.markRejected(tx.id());
        return TransferResult.rejected(tx.decisionId(), "solde insuffisant");
      }
      if (type.settlesCounterparty()) {
        accounts.findByMsisdn(counterparty).ifPresent(b -> accounts.credit(b.id(), amountXaf));
      }
    } else {
      accounts.credit(initiator.id(), amountXaf);
    }
    transactions.markExecuted(tx.id());
    notifyExecuted(initiator, type);
    return new TransferResult(state, tx.decisionId(), null);
  }

  /**
   * Notifie l'initiateur qu'une opération a été exécutée (EF-09). L'émission est NON BLOQUANTE :
   * une notification en échec ne défait pas une transaction déjà exécutée. Le contenu est fixe et
   * propre (ni lien, ni numéro, ni demande de secret), donc jamais refusé par le garde-fou ENF-17.
   */
  private void notifyExecuted(Account initiator, OperationType type) {
    try {
      notifications.emit(
          initiator.msisdn(),
          NotificationChannel.SMS,
          type.name() + "_EXECUTED",
          "Si vous n'êtes pas à l'origine de cette opération, rendez-vous en agence.");
    } catch (RuntimeException e) {
      log.warn("notification post-opération non émise : {}", e.getMessage());
    }
  }

  private String buildRequest(
      String decisionId,
      Account from,
      OperationType type,
      String counterparty,
      long amountXaf,
      String channel,
      Instant at) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("decisionId", decisionId);
    req.put("accountId", from.id().toString());
    req.put("msisdn", from.msisdn());
    // IMSI courant résolu depuis le HLR (T2-10) ; PLACEHOLDER seulement si le numéro n'a pas encore
    // d'IMSI enregistré. Le MOTIF d'une éventuelle réattribution N'EST JAMAIS ajouté ici : le cœur
    // ne
    // transmet pas le motif au PDP (ES-16b), il ne fait que le journaliser côté HLR.
    // IMSI courant du HLR : garanti présent à ce stade (voir le refus A5-00 dans operate()).
    req.put(
        "currentImsi",
        hlr.currentImsi(from.msisdn())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "A5-00 : HLR sans IMSI atteint buildRequest : operate() a dû le vérifier"
                            + " avant")));
    req.put("channel", channel);
    req.put("type", type.name());
    req.put("amountXaf", amountXaf);
    req.put("beneficiaryId", counterparty);
    req.put("serverTimestamp", DateTimeFormatter.ISO_INSTANT.format(at));
    req.put("proofType", "NONE");
    // A4 (V4) : solde courant, que le PDP fige à l'entrée en PROBATION et compare au montant.
    req.put("balanceXaf", from.balanceXaf());
    // A4 (Q4) : date du dernier changement d'IMSI, lue dans l'historique HLR immuable (EF-08,
    // ADR-032), triée par occurred_at ; absente si le numéro n'a jamais été réattribué. Le motif
    // n'est jamais transmis (ES-16b).
    List<HlrReassignment> swaps = hlr.history(from.msisdn());
    if (!swaps.isEmpty()) {
      req.put(
          "imsiChangedAt",
          DateTimeFormatter.ISO_INSTANT.format(swaps.get(swaps.size() - 1).occurredAt()));
    }
    try {
      return json.writeValueAsString(req);
    } catch (Exception e) {
      throw new IllegalStateException("requete /evaluate non serialisable", e);
    }
  }

  /**
   * Requête /evaluate EXACTE que le cœur émettrait pour ce transfert, construite par le MÊME chemin
   * que {@link #transferP2P} (dont l'IMSI courant résolu depuis le HLR). Sert à INSPECTER la charge
   * utile émise — preuve que le motif de réattribution n'y figure pas (ES-16b) — sans créer de
   * transaction. Le decisionId est un marqueur d'inspection.
   */
  // @implements ES-16b
  public String evaluateRequestPreview(
      String fromMsisdn, String beneficiaryMsisdn, long amountXaf, String channel) {
    Account from =
        accounts
            .findByMsisdn(fromMsisdn)
            .orElseThrow(() -> new IllegalArgumentException("compte inconnu : " + fromMsisdn));
    return buildRequest(
        "inspection",
        from,
        OperationType.P2P,
        beneficiaryMsisdn,
        amountXaf,
        channel,
        clock.instant());
  }
}
