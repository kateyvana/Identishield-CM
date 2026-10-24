package cm.identishield.core.mobile;

import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.MobileLedger.AccountView;
import cm.identishield.core.mobile.MobileLedger.TransactionView;
import cm.identishield.core.mobile.SessionTokens.LiveSession;
import cm.identishield.core.transfer.TransferService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  private final MobileLedger ledger;
  private final DeviceClient device;
  private final TransferService transfers;

  public MobileDaily(MobileLedger ledger, DeviceClient device, TransferService transfers) {
    this.ledger = ledger;
    this.device = device;
    this.transfers = transfers;
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
    return Answer.ok(Map.of("items", List.of()));
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
