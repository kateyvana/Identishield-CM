package cm.identishield.core.transfer;

import cm.identishield.core.account.Account;
import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.HlrService;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Transfert P2P (EF-04) avec appel au PDP AVANT commit (EF-05). Le fil du squelette (ISC-SKEL §2) :
 * creer la transaction PENDING_DECISION (+ decisionId genere en base) -> POST /evaluate en mTLS ->
 * appliquer la decision (ALLOW => debit + EXECUTED). Si le PDP est indisponible, politique
 * fail-closed (ES-11) : refus au-dessus de CAP_PROB, mode degrade en dessous.
 *
 * <p>Perimetre mois 2 (ISC-SKEL §3) : un seul type (P2P), aucune regle, aucune machine a etats de
 * confiance. currentImsi est un PLACEHOLDER en attendant le HLR (T2-10). decisionId genere en base
 * (gen_random_uuid), jamais UUID.randomUUID.
 */
// @implements EF-04
// @implements EF-05
@Service
public class TransferService {

  private static final Logger log = LoggerFactory.getLogger(TransferService.class);
  // IMSI de remplacement tant que le HLR (T2-10) n'associe pas MSISDN -> IMSI.
  private static final String PLACEHOLDER_IMSI = "624010000000000";

  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final PdpClient pdp;
  private final RuleSet ruleSet;
  private final Clock clock;
  private final ObjectMapper json;
  private final HlrService hlr;

  public TransferService(
      AccountRepository accounts,
      TransactionRepository transactions,
      PdpClient pdp,
      RuleSet ruleSet,
      Clock clock,
      ObjectMapper json,
      HlrService hlr) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.pdp = pdp;
    this.ruleSet = ruleSet;
    this.clock = clock;
    this.json = json;
    this.hlr = hlr;
  }

  public TransferResult transferP2P(
      String fromMsisdn, String beneficiaryMsisdn, long amountXaf, String channel) {
    Account from =
        accounts
            .findByMsisdn(fromMsisdn)
            .orElseThrow(() -> new IllegalArgumentException("compte inconnu : " + fromMsisdn));
    Instant now = clock.instant();
    PendingTransaction tx =
        transactions.createPending(from.id(), "P2P", amountXaf, beneficiaryMsisdn, channel, now);

    EvaluateOutcome outcome =
        pdp.evaluate(
            buildRequest(tx.decisionId(), from, beneficiaryMsisdn, amountXaf, channel, now));

    if (outcome.available()) {
      if (outcome.isAllow()) {
        return execute(tx, from, beneficiaryMsisdn, amountXaf, TransferResult.State.EXECUTED);
      }
      transactions.markRejected(tx.id());
      return TransferResult.rejected(tx.decisionId(), "decision=" + outcome.decision());
    }

    // PDP indisponible : fail-closed (ES-11).
    Outcome policy = new FailClosedPolicy(ruleSet.capProbDailyXaf()).onPdpUnavailable(amountXaf);
    if (policy == Outcome.REFUSED_FAIL_CLOSED) {
      transactions.markRejected(tx.id());
      log.warn(
          "PDP indisponible : transfert de {} XAF REFUSE (> CAP_PROB), fail-closed", amountXaf);
      return TransferResult.rejected(
          tx.decisionId(), "fail-closed : PDP indisponible, montant > CAP_PROB");
    }
    log.warn("PDP indisponible : transfert de {} XAF en mode DEGRADE (<= CAP_PROB)", amountXaf);
    return execute(tx, from, beneficiaryMsisdn, amountXaf, TransferResult.State.EXECUTED_DEGRADED);
  }

  private TransferResult execute(
      PendingTransaction tx,
      Account from,
      String beneficiaryMsisdn,
      long amountXaf,
      TransferResult.State state) {
    if (!accounts.debit(from.id(), amountXaf)) {
      transactions.markRejected(tx.id());
      return TransferResult.rejected(tx.decisionId(), "solde insuffisant");
    }
    accounts.findByMsisdn(beneficiaryMsisdn).ifPresent(b -> accounts.credit(b.id(), amountXaf));
    transactions.markExecuted(tx.id());
    return new TransferResult(state, tx.decisionId(), null);
  }

  private String buildRequest(
      String decisionId,
      Account from,
      String beneficiaryMsisdn,
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
    req.put("currentImsi", hlr.currentImsi(from.msisdn()).orElse(PLACEHOLDER_IMSI));
    req.put("channel", channel);
    req.put("type", "P2P");
    req.put("amountXaf", amountXaf);
    req.put("beneficiaryId", beneficiaryMsisdn);
    req.put("serverTimestamp", DateTimeFormatter.ISO_INSTANT.format(at));
    req.put("proofType", "NONE");
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
    return buildRequest("inspection", from, beneficiaryMsisdn, amountXaf, channel, clock.instant());
  }
}
