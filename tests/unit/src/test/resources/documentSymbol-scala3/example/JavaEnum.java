package /*example(Module):1*/example;

public enum /*example.JavaEnum(Class):3*/JavaEnum {
  /*example.JavaEnum#A(Field):4*/A(1),
  /*example.JavaEnum#B(Field):5*/B(2),
  /*example.JavaEnum#C(Field):6*/C(Magic.E);

  public static final JavaEnum /*example.JavaEnum#D(Field):8*/D = B;

  public static class /*example.JavaEnum#Magic(Class):10*//*example.JavaEnum#Magic#Magic(Constructor):10*/Magic {
    public static final int /*example.JavaEnum#Magic#E(Field):11*/E = 42;
  }

  /*example.JavaEnum#JavaEnum(Constructor):14*/JavaEnum(int d) {
    this.d = d;
  }

  public int /*example.JavaEnum#d(Field):18*/d = 2;
}