package tests.mbt

class MbtImplementationSuite
    extends BaseMbtReferenceSuite("mbt-implementation") {

  // FIXME: this test fails about ~10% of runs
  // reproduce by adding `.tag(tests.Rerun(10))`
  testLSP("basic".flaky) {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Animal.java"
    val b = "b/src/main/scala/b/Bat.java"
    val c = "c/src/main/scala/c/Ferrari.java"
    val d = "d/src/main/scala/d/FruitBat.java"
    val e = "e/src/main/scala/e/Main.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {},
           |  "b": {"dependsOn": ["a"]}
           |}
           |/$a
           |package a;
           |public abstract class Animal {
           |  public abstract String makeSound();
           |  public class Dog extends Animal {
           |    @Override public String makeSound() { return "Woof"; }
           |  }
           |}
           |/$b
           |package b;
           |public class Bat extends a.Animal {
           |  @Override public String makeSound() { return "Squeak"; }
           |  public void fly() {
           |    System.out.println("Flying");
           |  }
           |}
           |/$c
           |package c;
           |public class Ferrari {
           |  public String makeSound() {
           |    return "Vroom";
           |  }
           |}
           |/$d
           |package d;
           |import b.Bat;
           |import d.FruitBat.Kind;
           |public class FruitBat extends Bat {
           |  @Override public String makeSound() {
           |    return "Squeak";
           |  }
           |  public enum Kind {
           |    FRUIT,
           |    NO_FRUIT,
           |  }
           |}
           |/$e
           |package e;
           |import b.Bat;
           |public class Main {
           |  public static void main(String[] args) {
           |    var bat = new Bat();
           |    bat.makeSound();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "String make@@Sound()",
        """|a/src/main/scala/a/Animal.java:5:29: implementation
           |    @Override public String makeSound() { return "Woof"; }
           |                            ^^^^^^^^^
           |b/src/main/scala/b/Bat.java:3:27: implementation
           |  @Override public String makeSound() { return "Squeak"; }
           |                          ^^^^^^^^^
           |d/src/main/scala/d/FruitBat.java:5:27: implementation
           |  @Override public String makeSound() {
           |                          ^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "class Anim@@al",
        """|a/src/main/scala/a/Animal.java:4:16: implementation
           |  public class Dog extends Animal {
           |               ^^^
           |b/src/main/scala/b/Bat.java:2:14: implementation
           |public class Bat extends a.Animal {
           |             ^^^
           |d/src/main/scala/d/FruitBat.java:4:14: implementation
           |public class FruitBat extends Bat {
           |             ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("type-variables") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Foo.java"
    val b = "a/src/main/scala/a/Bar.java"
    val c = "a/src/main/scala/a/Baz.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a;
           |public abstract class Foo<T> {
           |  public abstract T bar(T elem);
           |}
           |/$b
           |package a;
           |public class Bar extends Foo<Integer> {
           |  @Override public Integer bar(Integer elem) { return elem; }
           |}
           |/$c
           |package a;
           |public class Baz extends Foo<String> {
           |  @Override public String bar(String elem) { return elem; }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "T b@@ar(T elem)",
        """|a/src/main/scala/a/Bar.java:3:28: implementation
           |  @Override public Integer bar(Integer elem) { return elem; }
           |                           ^^^
           |a/src/main/scala/a/Baz.java:3:27: implementation
           |  @Override public String bar(String elem) { return elem; }
           |                          ^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "class F@@oo<T>",
        """
          |a/src/main/scala/a/Bar.java:2:14: implementation
          |public class Bar extends Foo<Integer> {
          |             ^^^
          |a/src/main/scala/a/Baz.java:2:14: implementation
          |public class Baz extends Foo<String> {
          |             ^^^
          |""".stripMargin,
      )
    } yield ()
  }

  testLSP("interface-multiple-inheritance") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Top.java"
    val b = "a/src/main/scala/a/Left.java"
    val c = "a/src/main/scala/a/Right.java"
    val d = "a/src/main/scala/a/Bottom.java"
    val e = "a/src/main/scala/a/Direct.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a;
           |public interface Top {
           |  void method();
           |}
           |/$b
           |package a;
           |public interface Left extends Top {}
           |/$c
           |package a;
           |public interface Right extends Top {}
           |/$d
           |package a;
           |public class Bottom implements Left, Right {
           |  @Override public void method() {}
           |}
           |/$e
           |package a;
           |public class Direct implements Top {
           |  @Override public void method() {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "void meth@@od()",
        """|a/src/main/scala/a/Bottom.java:3:25: implementation
           |  @Override public void method() {}
           |                        ^^^^^^
           |a/src/main/scala/a/Direct.java:3:25: implementation
           |  @Override public void method() {}
           |                        ^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "interface T@@op",
        """|a/src/main/scala/a/Bottom.java:2:14: implementation
           |public class Bottom implements Left, Right {
           |             ^^^^^^
           |a/src/main/scala/a/Direct.java:2:14: implementation
           |public class Direct implements Top {
           |             ^^^^^^
           |a/src/main/scala/a/Left.java:2:18: implementation
           |public interface Left extends Top {}
           |                 ^^^^
           |a/src/main/scala/a/Right.java:2:18: implementation
           |public interface Right extends Top {}
           |                 ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
