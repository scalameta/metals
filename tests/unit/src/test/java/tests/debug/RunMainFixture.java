package tests.debug;

public final class RunMainFixture {
  public static void main(String[] arguments) {
    System.out.println(
        System.getProperty("run-main-test")
            + "|"
            + String.join(",", arguments)
            + "|"
            + System.getenv("RUN_MAIN_TEST"));
    System.exit(0);
  }
}
