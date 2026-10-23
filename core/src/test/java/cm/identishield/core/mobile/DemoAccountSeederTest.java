package cm.identishield.core.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.hlr.HlrService;
import cm.identishield.core.transfer.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Comptes de test (carte B3a, décision 6) : l'amorçage n'existe PAS hors du profil demo, et la
 * seule source est le fichier de fixtures du faux serveur.
 */
class DemoAccountSeederTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
          .withBean(HlrService.class, () -> mock(HlrService.class))
          .withBean(PinStore.class, () -> mock(PinStore.class))
          .withBean(PinHasher.class, () -> mock(PinHasher.class))
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withPropertyValues("core.demo.fixtures-path=../tools/mobile-mock/fixtures.json")
          .withUserConfiguration(DemoAccountSeeder.class);

  @Test
  void theSeederDoesNotExist_withoutTheDemoProfile() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DemoAccountSeeder.class));
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(DemoAccountSeeder.class));
  }

  @Test
  void theSeederExists_underTheDemoProfile() {
    runner
        .withPropertyValues("spring.profiles.active=demo")
        .run(ctx -> assertThat(ctx).hasSingleBean(DemoAccountSeeder.class));
  }

  @Test
  void theDerivedImsi_isAnMtnImsiOf15Digits() {
    String imsi = DemoAccountSeeder.imsiFor("677000200");
    assertEquals(15, imsi.length());
    assertTrue(imsi.startsWith("62401"));
  }

  /**
   * Aucun IMSI de démonstration n'est égal au PLACEHOLDER_IMSI du cœur (carte A5-00) : une
   * collision serait incompréhensible à déboguer. La constante est lue dans TransferService.
   */
  @Test
  void noDemoImsi_equalsTheCorePlaceholderImsi() throws Exception {
    Field f = TransferService.class.getDeclaredField("PLACEHOLDER_IMSI");
    f.setAccessible(true);
    String placeholder = (String) f.get(null);
    List<String> msisdns = new ArrayList<>(List.of("677000000", "677009999"));
    new ObjectMapper()
        .readTree(Files.readString(Path.of("../tools/mobile-mock/fixtures.json")))
        .path("accounts")
        .forEach(a -> msisdns.add(a.path("msisdn").asText()));
    assertTrue(msisdns.size() > 2, "les numéros des fixtures ont été lus");
    for (String msisdn : msisdns) {
      assertNotEquals(placeholder, DemoAccountSeeder.imsiFor(msisdn), msisdn);
    }
  }
}
