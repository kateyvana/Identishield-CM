package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Prêt par construction (décision du 2026-10-25) : {@code /health} est un vrai aller-retour en base
 * — 200 seulement si la base répond, 503 sinon — et le pool s'ouvre à la construction de {@link
 * DatabaseReadiness}, avant le port.
 *
 * <p>@covers ES-11
 */
class HealthControllerTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  @Test
  void theConstructor_opensThePool_withARealRoundTrip_beforeAnyRequest() {
    when(jdbc.queryForObject(DatabaseReadiness.PROBE_SQL, Integer.class)).thenReturn(1);
    new DatabaseReadiness(jdbc);
    verify(jdbc, times(1)).queryForObject(eq(DatabaseReadiness.PROBE_SQL), eq(Integer.class));
  }

  @Test
  void aDatabaseUnreachableAtStartup_preventsTheServiceFromStarting() {
    when(jdbc.queryForObject(DatabaseReadiness.PROBE_SQL, Integer.class))
        .thenThrow(new CannotGetJdbcConnectionException("refused"));
    assertThrows(CannotGetJdbcConnectionException.class, () -> new DatabaseReadiness(jdbc));
  }

  @Test
  void health_is200Up_onlyWhenTheDatabaseAnswersNow() {
    when(jdbc.queryForObject(DatabaseReadiness.PROBE_SQL, Integer.class)).thenReturn(1);
    HealthController controller = new HealthController(new DatabaseReadiness(jdbc));

    ResponseEntity<?> up = controller.health();
    assertEquals(200, up.getStatusCode().value());
    assertEquals("UP", ((java.util.Map<?, ?>) up.getBody()).get("status"));
    // Un aller-retour à la construction, un par appel : jamais de valeur mise en cache.
    verify(jdbc, times(2)).queryForObject(eq(DatabaseReadiness.PROBE_SQL), eq(Integer.class));
  }

  @Test
  void health_is503Down_whenTheDatabaseStopsAnswering() {
    when(jdbc.queryForObject(DatabaseReadiness.PROBE_SQL, Integer.class))
        .thenReturn(1)
        .thenThrow(new CannotGetJdbcConnectionException("gone"));
    HealthController controller = new HealthController(new DatabaseReadiness(jdbc));

    ResponseEntity<?> down = controller.health();
    assertEquals(503, down.getStatusCode().value());
    assertEquals("DOWN", ((java.util.Map<?, ?>) down.getBody()).get("status"));
  }
}
