package example;

public enum JavaEnum {
  A(1),
  B(2),
  C(Magic.E);

  public static final JavaEnum D = B;

  public static class Magic {
    public static final int E = 42;
  }

  JavaEnum(int d) {
    this.d = d;
  }

  public int d = 2;
}
