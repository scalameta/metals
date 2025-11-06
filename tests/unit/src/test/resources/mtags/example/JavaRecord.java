package example;

public record JavaRecord/*example.JavaRecord#*//*example.JavaRecord#`<init>`().*/(int a/*example.JavaRecord#a().*/, int b/*example.JavaRecord#b().*/) {
  public JavaRecord/*example.JavaRecord#`<init>`(+1).*/(int a) {
    this(a, 0);
  }

  public JavaRecord/*example.JavaRecord#`<init>`(+2).*/(int a, int b, int c) {
    this(a, b + c);
  }

  public static JavaRecord of/*example.JavaRecord#of().*/(int a) {
    return new JavaRecord(a);
  }

  public int sum/*example.JavaRecord#sum().*/() {
    return a + b;
  }
}
