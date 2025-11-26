package /*example(Module):1*/example;

public class /*example.JavaClass(Class):3*/JavaClass {

  private /*example.JavaClass#JavaClass(Constructor):5*/JavaClass() {}

  public /*example.JavaClass#JavaClass(Constructor):7*/JavaClass(int d) {
    this.d = d;
  }

  public static void /*example.JavaClass#a(Method):11*/a() {
    new JavaClass(42);
  }

  public int /*example.JavaClass#b(Method):15*/b() {
    return 1;
  }

  public static int /*example.JavaClass#c(Field):19*/c = 2;
  public int /*example.JavaClass#d(Field):20*/d = 2;

  public class /*example.JavaClass#InnerClass(Class):22*//*example.JavaClass#InnerClass#InnerClass(Constructor):22*/InnerClass {
    public int /*example.JavaClass#InnerClass#b(Method):23*/b() {
      return 1;
    }

    public int /*example.JavaClass#InnerClass#d(Field):27*/d = 2;
  }

  public static class /*example.JavaClass#InnerStaticClass(Class):30*//*example.JavaClass#InnerStaticClass#InnerStaticClass(Constructor):30*/InnerStaticClass {
    public static void /*example.JavaClass#InnerStaticClass#a(Method):31*/a() {}

    public int /*example.JavaClass#InnerStaticClass#b(Method):33*/b() {
      return 1;
    }

    public static int /*example.JavaClass#InnerStaticClass#c(Field):37*/c = 2;
    public int /*example.JavaClass#InnerStaticClass#d(Field):38*/d = 2;
  }

  public interface /*example.JavaClass#InnerInterface(Interface):41*/InnerInterface {
    public static void /*example.JavaClass#InnerInterface#a(Method):42*/a() {}

    public int /*example.JavaClass#InnerInterface#b(Method):44*/b();
  }

  public String /*example.JavaClass#publicName(Method):47*/publicName() {
    return "name";
  }

  // Weird formatting
  @Override
  public String /*example.JavaClass#toString(Method):53*/toString() {
    return "";
  }
}