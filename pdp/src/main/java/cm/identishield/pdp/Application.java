package cm.identishield.pdp;

import cm.identishield.pdp.config.RuleSet;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Entry point of isc-pdp. Loads the versioned rule set (EF-17). */
@SpringBootApplication
@EnableConfigurationProperties(RuleSet.class)
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
