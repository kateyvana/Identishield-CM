package cm.identishield.pdp.web;

import cm.identishield.pdp.trust.TrustStateMachine;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lecture de l'état de confiance d'un compte (ADR-041, amendement du 2026-10-24, décision du
 * superviseur technique) : route INTERNE du PDP (mTLS cœur↔PDP, jamais sur le 8443), LECTURE SEULE
 * — aucune écriture en base, aucune entrée d'audit. Le cœur la consomme pour {@code GET
 * /mobile/v1/account} (état affiché à l'usager). Un compte que le PDP n'a jamais vu : 404.
 *
 * <p>Les échéances passées (fin de probation, TTL de gel) sont appliquées EN MÉMOIRE seulement, par
 * les mêmes règles que {@code /evaluate} ({@link TrustStateMachine#peek}) : l'état lu est celui que
 * la prochaine décision constaterait, sans l'écrire à sa place.
 */
// @implements EF-13
@RestController
public class TrustController {

  /** Réponse : l'état et ses trois instants ; {@code null} quand sans objet. */
  public record TrustResponse(
      String state, String since, String probationUntil, String freezeUntil) {}

  private final TrustStateMachine trust;

  public TrustController(TrustStateMachine trust) {
    this.trust = trust;
  }

  @GetMapping("/trust/{accountId}")
  public ResponseEntity<Object> read(@PathVariable String accountId) {
    return trust
        .peek(accountId)
        .<ResponseEntity<Object>>map(
            t ->
                ResponseEntity.ok(
                    new TrustResponse(
                        t.state().name(),
                        iso(t.since()),
                        iso(t.probationUntil()),
                        iso(t.freezeUntil()))))
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiError("ACCOUNT_UNKNOWN", "no trust state for this account")));
  }

  private static String iso(Instant i) {
    return i == null ? null : i.toString();
  }
}
