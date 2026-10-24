package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.Alert;
import cm.identishield.pdp.trust.TrustStateMachine.FreezeCause;
import cm.identishield.pdp.trust.TrustStateMachine.Refused;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import cm.identishield.pdp.web.TrustController.NotMeRequest;
import cm.identishield.pdp.web.TrustController.NotMeResponse;
import cm.identishield.pdp.web.TrustController.TrustResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * GET /trust/{accountId} : énonce l'état lu par {@code peek}, 404 pour un compte jamais vu, aucune
 * mutation. POST /trust/{accountId}/not-me : applique {@code notMe} avec l'alertId tracé, 200 avec
 * l'échéance du gel des ré-enrôlements, 409 quand la machine refuse.
 *
 * <p>@covers EF-13 ; @covers ES-27
 */
class TrustControllerTest {

  private final TrustStateMachine trust = mock(TrustStateMachine.class);
  private final TrustController controller = new TrustController(trust);

  private static final Instant SINCE = Instant.parse("2026-10-24T15:48:31.529168Z");
  private static final Instant UNTIL = Instant.parse("2026-10-25T15:48:31.529168Z");
  private static final String ALERT_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  private static Trust trust(State state, Instant freezeUntil, Instant reenrolFrozenUntil) {
    return new Trust(
        "acc-1",
        state,
        SINCE,
        "624010000000000",
        "624010000000000",
        null,
        state == State.FROZEN ? FreezeCause.NOT_ME : null,
        freezeUntil,
        reenrolFrozenUntil,
        null,
        null);
  }

  @Test
  void aFrozenAccount_isStatedWithItsDeadlines_nullWhenNotApplicable() {
    when(trust.peek("acc-1"))
        .thenReturn(
            Optional.of(
                new Trust(
                    "acc-1",
                    State.FROZEN,
                    SINCE,
                    "624010000000000",
                    "624010000000000",
                    null,
                    FreezeCause.FREEZE_DECISION,
                    UNTIL,
                    null,
                    null,
                    null)));
    ResponseEntity<Object> r = controller.read("acc-1");
    assertEquals(200, r.getStatusCode().value());
    assertEquals(
        new TrustResponse("FROZEN", SINCE.toString(), null, UNTIL.toString()), r.getBody());
    verify(trust).peek("acc-1");
    verifyNoMoreInteractions(trust); // lecture seule : ni current(), ni mutation
  }

  @Test
  void anUnknownAccount_is404_accountUnknown() {
    when(trust.peek("acc-9")).thenReturn(Optional.empty());
    ResponseEntity<Object> r = controller.read("acc-9");
    assertEquals(404, r.getStatusCode().value());
    assertEquals("ACCOUNT_UNKNOWN", ((ApiError) r.getBody()).code());
    verify(trust).peek("acc-9");
    verifyNoMoreInteractions(trust);
  }

  // ---- « ce n'est pas moi » (ES-27, A1-4) ---------------------------------------------------

  @Test
  void notMe_simChange_appliesTheFreeze_withTheAlertIdTraced_andStatesTheReenrolDeadline() {
    when(trust.notMe(eq("acc-1"), eq(Alert.SIM_CHANGE), eq(Map.of("alertId", ALERT_ID))))
        .thenReturn(trust(State.FROZEN, null, UNTIL));
    ResponseEntity<Object> r = controller.notMe("acc-1", new NotMeRequest("SIM_CHANGE", ALERT_ID));
    assertEquals(200, r.getStatusCode().value());
    assertEquals(
        new NotMeResponse("FROZEN", SINCE.toString(), null, null, UNTIL.toString()), r.getBody());
    verify(trust).notMe("acc-1", Alert.SIM_CHANGE, Map.of("alertId", ALERT_ID));
    verifyNoMoreInteractions(trust);
  }

  @Test
  void notMe_reenrolAttempt_leavesTheStateToTheMachine_trustedStaysTrusted() {
    when(trust.notMe(eq("acc-1"), eq(Alert.REENROL_ATTEMPT), any()))
        .thenReturn(trust(State.TRUSTED, null, UNTIL));
    ResponseEntity<Object> r =
        controller.notMe("acc-1", new NotMeRequest("REENROL_ATTEMPT", ALERT_ID));
    assertEquals(200, r.getStatusCode().value());
    assertEquals("TRUSTED", ((NotMeResponse) r.getBody()).state());
    assertEquals(UNTIL.toString(), ((NotMeResponse) r.getBody()).reenrolFrozenUntil());
  }

  @Test
  void notMe_whenTheMachineRefuses_is409_alreadyFrozen_andDecidesNothingElse() {
    when(trust.notMe(eq("acc-1"), eq(Alert.SIM_CHANGE), any()))
        .thenThrow(new Refused("account already frozen by NOT_ME: acc-1"));
    ResponseEntity<Object> r = controller.notMe("acc-1", new NotMeRequest("SIM_CHANGE", ALERT_ID));
    assertEquals(409, r.getStatusCode().value());
    assertEquals("ALREADY_FROZEN", ((ApiError) r.getBody()).code());
    verify(trust).notMe(eq("acc-1"), eq(Alert.SIM_CHANGE), any());
    verifyNoMoreInteractions(trust);
  }
}
