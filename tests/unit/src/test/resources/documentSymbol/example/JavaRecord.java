package /*example(Module):1*/example;

public record /*example.JavaRecord(Class):3*//*example.JavaRecord#JavaRecord(Constructor):3*/JavaRecord(int /*example.JavaRecord#a(Method):3*/a, int /*example.JavaRecord#b(Method):3*/b) {
  public /*example.JavaRecord#JavaRecord(Constructor):4*/JavaRecord(int a) {
    this(a, 0);
  }

  public /*example.JavaRecord#JavaRecord(Constructor):8*/JavaRecord(int a, int b, int c) {
    this(a, b + c);
  }

  public static JavaRecord /*example.JavaRecord#of(Method):12*/of(int a) {
    return new JavaRecord(a);
  }

  public int /*example.JavaRecord#sum(Method):16*/sum() {
    return a + b;
  }
}