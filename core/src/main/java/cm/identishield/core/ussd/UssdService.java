package cm.identishield.core.ussd;

import cm.identishield.core.transfer.TransferResult;
import cm.identishield.core.transfer.TransferService;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Canal USSD simulé (EF-06) : une machine à états de session, UNE branche au mois 2 (transfert
 * P2P). Aucune capacité cryptographique côté client. La branche mène au {@link TransferService} qui
 * appelle /evaluate avant commit (le fil ISC-SKEL §2).
 *
 * <p>Revue ligne à ligne requise : machine à états.
 */
// @implements EF-06
@Service
public class UssdService {

  private static final Duration SESSION_TTL = Duration.ofMinutes(5);

  private final UssdRepository sessions;
  private final TransferService transfers;
  private final Clock clock;

  public UssdService(UssdRepository sessions, TransferService transfers, Clock clock) {
    this.sessions = sessions;
    this.transfers = transfers;
    this.clock = clock;
  }

  /** Première interaction (composition du code USSD) : ouvre la session, demande le montant. */
  public Interaction dial(String msisdn) {
    UUID id = sessions.create(msisdn, UssdSession.Step.AMOUNT, clock.instant().plus(SESSION_TTL));
    return new Interaction(id, new UssdResponse("Transfert P2P. Montant en FCFA ?", true));
  }

  /** Une saisie de l'usager fait avancer la machine à états. */
  public UssdResponse input(UUID sessionId, String text) {
    Optional<UssdSession> active = sessions.findActive(sessionId, clock.instant());
    if (active.isEmpty()) {
      return new UssdResponse("Session expiree ou inconnue.", false);
    }
    UssdSession s = active.get();
    return switch (s.step()) {
      case AMOUNT -> onAmount(s, text);
      case BENEFICIARY -> onBeneficiary(s, text);
      case CONFIRM -> onConfirm(s, text);
    };
  }

  private UssdResponse onAmount(UssdSession s, String text) {
    long amount;
    try {
      amount = Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return new UssdResponse("Montant invalide. Montant en FCFA ?", true);
    }
    if (amount < 1) {
      return new UssdResponse("Montant invalide. Montant en FCFA ?", true);
    }
    sessions.update(
        new UssdSession(s.id(), s.msisdn(), UssdSession.Step.BENEFICIARY, amount, null));
    return new UssdResponse("MSISDN du beneficiaire ?", true);
  }

  private UssdResponse onBeneficiary(UssdSession s, String text) {
    String beneficiary = text.trim();
    sessions.update(
        new UssdSession(s.id(), s.msisdn(), UssdSession.Step.CONFIRM, s.amountXaf(), beneficiary));
    return new UssdResponse(
        "Confirmer " + s.amountXaf() + " FCFA vers " + beneficiary + " ? 1=Oui", true);
  }

  private UssdResponse onConfirm(UssdSession s, String text) {
    if (!"1".equals(text.trim())) {
      sessions.delete(s.id());
      return new UssdResponse("Transfert annule.", false);
    }
    TransferResult r = transfers.transferP2P(s.msisdn(), s.beneficiary(), s.amountXaf(), "USSD");
    sessions.delete(s.id());
    return new UssdResponse("Transfert " + r.state() + ". Reference " + r.decisionId(), false);
  }

  /** Une session ouverte : son identifiant et la première réponse. */
  public record Interaction(UUID sessionId, UssdResponse response) {}
}
