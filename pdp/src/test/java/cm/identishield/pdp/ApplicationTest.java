package cm.identishield.pdp;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Trivial test required by TACHE-00 task 5: the build and test toolchain work. */
class ApplicationTest {

  @Test
  void applicationClassIsPresent() {
    assertNotNull(Application.class);
  }
}
