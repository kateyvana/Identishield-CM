package cm.identishield.pdp.web;

import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.Alert;
import cm.identishield.pdp.trust.TrustStateMachine.Refused;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * État de confiance d'un compte, routes INTERNES du PDP (mTLS cœur↔PDP, jamais sur le 8443).
 *
 * <p>{@code GET /trust/{accountId}} (ADR-041, amendement du 2026-10-24) : LECTURE SEULE — aucune
 * écriture en base, aucune entrée d'audit. Le cœur la consomme pour {@code GET /mobile/v1/account}.
 * Les échéances passées sont appliquées EN MÉMOIRE par les mêmes règles que {@code /evaluate}
 * ({@link TrustStateMachine#peek}). Un compte que le PDP n'a jamais vu : 404.
 *
 * <p>{@code POST /trust/{accountId}/not-me} (ADR-041, amendement du 2026-10-25 ; ES-27, arbitrage
 * A1-4) : la réponse signée « ce n'est pas moi » du titulaire, vérifiée AVANT par le cœur ({@code
 * /device/verify}, chaîne {@code accountId|ALERT_NOT_ME|alertId|nonce}). Effet = {@link
 * TrustStateMachine#notMe} : SIM_CHANGE → FROZEN (cause NOT_ME, sans échéance) et ré-enrôlements
 * gelés REENROL_FREEZE ; REENROL_ATTEMPT → ré-enrôlements gelés seulement. Chaque événement est
 * écrit dans l'audit avec sa cause et l'{@code alertId}. Compte jamais vu : créé comme pour toute
 * transition. Déjà gelé NOT_ME : 409, l'effet est déjà en place. Le PDP énonce, le cœur arbitre ;
 * le gel est un acte du PDP.
 */
// @implements EF-13
// @implements ES-27
@RestController
public class TrustController {

  /** Réponse de GET : l'état et ses trois instants ; {@code null} quand sans objet. */
  public record TrustResponse(
      String state, String since, String probationUntil, String freezeUntil) {}

  /** Corps de POST /trust/{accountId}/not-me (contracts/device.yaml). */
  public record NotMeRequest(
      @NotBlank @Pattern(regexp = "SIM_CHANGE|REENROL_ATTEMPT") String alert,
      @NotBlank
          @Pattern(
              regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
              message = "alertId must be a UUID")
          String alertId) {}

  /** Réponse de POST : la forme de GET plus l'échéance du gel des ré-enrôlements. */
  public record NotMeResponse(
      String state,
      String since,
      String probationUntil,
      String freezeUntil,
      String reenrolFrozenUntil) {}

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

  @PostMapping("/trust/{accountId}/not-me")
  public ResponseEntity<Object> notMe(
      @PathVariable String accountId, @Valid @RequestBody NotMeRequest r) {
    try {
      Trust t = trust.notMe(accountId, Alert.valueOf(r.alert()), Map.of("alertId", r.alertId()));
      return ResponseEntity.ok(
          new NotMeResponse(
              t.state().name(),
              iso(t.since()),
              iso(t.probationUntil()),
              iso(t.freezeUntil()),
              iso(t.reenrolFrozenUntil())));
    } catch (Refused e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(new ApiError("ALREADY_FROZEN", "account already frozen by a not-me answer"));
    }
  }

  private static String iso(Instant i) {
    return i == null ? null : i.toString();
  }
}
