package example;

public class JavaSemicolon {
  public static void a() {}

  public int b() {
    return 1;
  }
  ;

  public static int c = 2;

  public class C {
    public int b() {
      return 1;
    }
    ;

    public int d = 2;
  }

  public static class F {
    public static void a() {}

    public int b() {
      return 1;
    }
    ;

    public static int c = 2;
    public int d = 2;
  }
}
