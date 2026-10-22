package cm.identishield.pdp.web;

/** Lanceur du banc hors JUnit (exécution dans la VM, classpath exporté). */
public final class BenchMain {
  public static void main(String[] a) throws Exception {
    new EvaluateLatencyBench().bench();
  }
}
