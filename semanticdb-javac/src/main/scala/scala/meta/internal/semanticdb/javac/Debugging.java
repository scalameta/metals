package scala.meta.internal.semanticdb.javac;

/** Utility methods for debugging purposes. */
public final class Debugging {
  public static void pprint(Object any) {
    StackTraceElement trace = new Exception().getStackTrace()[1];
    if (any instanceof String) {
      any = String.format("\"%s\"", any);
    }
    System.out.printf("%s:%s %s%n", trace.getFileName(), trace.getLineNumber(), any);
  }
}
