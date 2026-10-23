package cm.identishield.core.mobile;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tri par port de la surface mobile (L7-a ; ADR-036, point 5 : « aucune autre route du cœur n'est
 * joignable » par l'entrée publiée).
 *
 * <p>UN SEUL INTERRUPTEUR ({@code core.mobile-tls.enabled}) commande à la fois le connecteur HTTPS
 * et ce tri (décision de la propriétaire du 2026-10-23). Éteint : ce filtre laisse tout passer, le
 * cœur se comporte exactement comme avant L7-a (le PIN en clair sur 8080 reste la limite déclarée
 * L7, jamais une surface mobile morte). Allumé :
 *
 * <ul>
 *   <li>sur le port HTTPS mobile, SEULES les routes /mobile/v1/* répondent ;
 *   <li>sur tout autre port (8080, isc-net interne), les routes /mobile/v1/* sont REFUSÉES : le PIN
 *       ne voyage plus jamais en clair.
 * </ul>
 *
 * <p>Le refus répond 403 avec une raison en texte clair (ce n'est pas une erreur du contrat). Les
 * routes sont publiées par le contrat, dépôt public : rien à cacher ; un 404 enverrait chercher au
 * mauvais endroit, un 403 motivé se diagnostique en dix secondes.
 *
 * <p>Le chemin jugé est celui que Tomcat a DÉCODÉ et NORMALISÉ (chemin de servlet + chemin
 * additionnel), jamais l'URI brute : « /mobile/v1/../hlr/reassign » est jugé comme « /hlr/reassign
 * », route interne. C'est ce chemin normalisé que Spring MVC aiguille ensuite.
 *
 * <p>Revue ligne à ligne requise : frontière d'exposition du cœur.
 */
// @implements ENF-07
// @implements EI-03
public class MobilePortFilter extends OncePerRequestFilter {

  static final String MOBILE_PREFIX = "/mobile/v1";
  static final String ONLY_MOBILE_HERE =
      "Refuse : seules les routes /mobile/v1/* sont servies sur ce port (HTTPS mobile).";
  static final String MOBILE_ONLY_OVER_TLS =
      "Refuse : les routes /mobile/v1/* ne sont servies qu'en HTTPS, sur le port mobile.";

  private final MobileTlsProperties tls;

  public MobilePortFilter(MobileTlsProperties tls) {
    this.tls = tls;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!tls.enabled()) {
      chain.doFilter(request, response);
      return;
    }
    boolean mobilePath = isMobile(normalizedPath(request));
    boolean mobilePort = request.getLocalPort() == tls.port() && request.isSecure();
    if (mobilePort && !mobilePath) {
      refuse(response, ONLY_MOBILE_HERE);
      return;
    }
    if (!mobilePort && mobilePath) {
      refuse(response, MOBILE_ONLY_OVER_TLS);
      return;
    }
    chain.doFilter(request, response);
  }

  static boolean isMobile(String path) {
    return path.equals(MOBILE_PREFIX) || path.startsWith(MOBILE_PREFIX + "/");
  }

  private static String normalizedPath(HttpServletRequest request) {
    String info = request.getPathInfo();
    return request.getServletPath() + (info == null ? "" : info);
  }

  private static void refuse(HttpServletResponse response, String reason) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("text/plain;charset=UTF-8");
    response.getOutputStream().write(reason.getBytes(StandardCharsets.UTF_8));
  }
}
