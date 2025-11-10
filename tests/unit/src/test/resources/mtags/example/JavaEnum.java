package example;

public enum JavaEnum/*example.JavaEnum#*/ {
  A/*example.JavaEnum#A.*/(1),
  B/*example.JavaEnum#B.*/(2),
  C/*example.JavaEnum#C.*/(Magic.E);

  public static final JavaEnum D/*example.JavaEnum#D.*/ = B;

  public static class Magic/*example.JavaEnum#Magic#*//*example.JavaEnum#Magic#`<init>`().*/ {
    public static final int E/*example.JavaEnum#Magic#E.*/ = 42;
  }

  JavaEnum/*example.JavaEnum#`<init>`().*/(int d) {
    this.d = d;
  }

  public int d/*example.JavaEnum#d.*/ = 2;
}
