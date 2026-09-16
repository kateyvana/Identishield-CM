package cm.identishield.core.otp;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Boîte de réception SIM simulée — le TRANSPORT du code OTP vers l'appareil (EF-03, AC-02). Dans le
 * monde réel, l'OTP part par SMS en clair vers l'IMSI courant ; qui détient la SIM lit le code. Ici
 * on modélise exactement ce transport : à l'émission, le code CLAIR est déposé dans la boîte de
 * l'IMSI cible ; qui interroge cette boîte pour un IMSI (donc qui détient cette SIM) lit le code.
 *
 * <p>C'est le mécanisme d'INTERCEPTION d'AC-02 : après un SIM swap, l'IMSI courant du numéro est
 * celui de l'attaquant, donc le code atterrit dans SA boîte. Le transport porte le clair (comme un
 * SMS) — c'est distinct du stockage de vérification, qui n'en garde qu'une empreinte (T2-12). En
 * mémoire, éphémère : ce n'est pas un journal, c'est un canal.
 */
@Component
public class SimInbox {

  // Dernier code livré à chaque IMSI. Transport volatil, jamais persisté.
  private final ConcurrentHashMap<String, String> delivered = new ConcurrentHashMap<>();

  /** Livre le code clair vers l'IMSI cible (transport SMS simulé). */
  public void deliver(String imsi, String code) {
    delivered.put(imsi, code);
  }

  /** Lit le dernier code livré à cet IMSI — l'interception par qui détient la SIM. */
  public Optional<String> read(String imsi) {
    return Optional.ofNullable(delivered.get(imsi));
  }
}
