package cm.identishield.pdp.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Prêt PAR CONSTRUCTION (décision du superviseur technique du 2026-10-25, qui tranche l'observation
 * du 2026-09-15 « premier /evaluate à froid ») : le pool de connexions s'ouvre ICI, à
 * l'initialisation des singletons, donc AVANT l'ouverture du port 8443 — plus jamais au premier
 * appel réel. Si la base n'est pas joignable, le PDP ne démarre pas (il ne peut rien décider sans
 * son journal) : rien n'est servi à moitié prêt.
 *
 * <p>{@link #ready()} est un VRAI aller-retour en base à chaque appel : c'est ce que {@code
 * /health} rapporte (200 seulement si prêt, 503 sinon), et ce que le cœur attend au démarrage.
 */
@Component
public class DatabaseReadiness {

  private static final Logger log = LoggerFactory.getLogger(DatabaseReadiness.class);
  static final String PROBE_SQL = "SELECT 1";

  private final JdbcTemplate jdbc;

  public DatabaseReadiness(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    // Ouvre le pool maintenant : première connexion, premier aller-retour, avant le port.
    jdbc.queryForObject(PROBE_SQL, Integer.class);
    log.info("base jointe à l'initialisation : pool ouvert avant l'ouverture du port");
  }

  /** Vrai si la base répond maintenant (aller-retour réel, jamais mis en cache). */
  public boolean ready() {
    try {
      return Integer.valueOf(1).equals(jdbc.queryForObject(PROBE_SQL, Integer.class));
    } catch (DataAccessException e) {
      log.warn("base injoignable : {}", e.getMostSpecificCause().getMessage());
      return false;
    }
  }
}
