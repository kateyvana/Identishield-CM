package cm.identishield.core.mobile;

import cm.identishield.core.mobile.SessionTokens.LiveSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Gardien des routes protégées de /mobile/v1 (carte B6) : lit le jeton porteur, le fait vivre par
 * {@link SessionTokens#touch} selon les trois durées de P-09, et rend la session vivante — ou rien.
 * Un jeton absent, inconnu, mort ou mal formé reçoit la MÊME absence : l'appelant n'apprend pas
 * lequel (ES-22, 401 CREDENTIALS_INVALID). {@code userActivity} est faux pour les requêtes
 * automatiques de l'application (interrogation des alertes), qui ne comptent pas comme activité.
 */
// @implements ES-15
// @implements ES-22
@Component
public class MobileSessions {

  private static final String BEARER = "Bearer ";

  private final SessionTokens sessions;

  public MobileSessions(SessionTokens sessions) {
    this.sessions = sessions;
  }

  public Optional<LiveSession> authenticate(HttpServletRequest http, boolean userActivity) {
    String header = http.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      return Optional.empty();
    }
    String token = header.substring(BEARER.length()).strip();
    return sessions.touch(token, userActivity);
  }
}
