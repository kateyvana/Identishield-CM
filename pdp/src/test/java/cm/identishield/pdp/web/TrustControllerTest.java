package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.FreezeCause;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import cm.identishield.pdp.web.TrustController.TrustResponse;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * GET /trust/{accountId} : énonce l'état lu par {@code peek}, 404 pour un compte jamais vu ; la
 * route ne fait rien d'autre (aucune mutation de la machine à états).
 *
 * <p>@covers EF-13
 */
class TrustControllerTest {

  private final TrustStateMachine trust = mock(TrustStateMachine.class);
  private final TrustController controller = new TrustController(trust);

  private static final Instant SINCE = Instant.parse("2026-10-24T15:48:31.529168Z");
  private static final Instant UNTIL = Instant.parse("2026-10-25T15:48:31.529168Z");

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
}
