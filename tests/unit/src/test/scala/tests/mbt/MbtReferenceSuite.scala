package tests.mbt

class MbtReferenceSuite extends BaseMbtReferenceSuite("mbt-reference") {

  testLSP("field") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Upstream.java"
    val b = "b/src/main/scala/b/B.java"
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
           |public class Upstream {
           |  public static String greeting = "Hello, World!";
           |  public static String greeting2 = "Hello, World!";
           |}
           |/$b
           |package b
           |import static a.Upstream.*;
           |public class B {
           |  public static void main(String[] args) {
           |    System.out.println(a.Upstream.greeting);
           |    System.out.println(greeting2);
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      // with qualifier
      _ <- server.assertReferencesSubquery(
        a,
        "String greeti@@ng",
        """
          |a/src/main/scala/a/Upstream.java:3:24: reference
          |  public static String greeting = "Hello, World!";
          |                       ^^^^^^^^
          |b/src/main/scala/b/B.java:5:35: reference
          |    System.out.println(a.Upstream.greeting);
          |                                  ^^^^^^^^
          |""".stripMargin,
      )
      // without qualifier
      _ <- server.assertReferencesSubquery(
        a,
        "String greeti@@ng2",
        """|a/src/main/scala/a/Upstream.java:4:24: reference
           |  public static String greeting2 = "Hello, World!";
           |                       ^^^^^^^^^
           |b/src/main/scala/b/B.java:6:24: reference
           |    System.out.println(greeting2);
           |                       ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("method") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Upstream.java"
    val b = "b/src/main/scala/b/B.java"
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
           |public class Upstream {
           |  public static String hello() { return "Hello, World!"; }
           |  public static String hello(int i) { return "Hello, World!"; }
           |  public String hello(String i) { return "Hello, World!"; }
           |  public static String hello2() { return "Hello, World!"; }
           |}
           |/$b
           |package b
           |import static a.Upstream.hello2;
           |public class B {
           |  public static void main(String[] args) {
           |    System.out.println(a.Upstream.hello());
           |    System.out.println(hello2());
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      // Asserts we find references that are qualified, and we don't include
      // other "hello()" overloads.
      _ <- server.assertReferencesSubquery(
        a,
        "hel@@lo",
        """|a/src/main/scala/a/Upstream.java:3:24: reference
           |  public static String hello() { return "Hello, World!"; }
           |                       ^^^^^
           |b/src/main/scala/b/B.java:5:35: reference
           |    System.out.println(a.Upstream.hello());
           |                                  ^^^^^
           |""".stripMargin,
      )
      // Asserts we find references that are not qualified
      _ <- server.assertReferencesSubquery(
        a,
        "hel@@lo2",
        """|a/src/main/scala/a/Upstream.java:6:24: reference
           |  public static String hello2() { return "Hello, World!"; }
           |                       ^^^^^^
           |b/src/main/scala/b/B.java:6:24: reference
           |    System.out.println(hello2());
           |                       ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("constructor") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Upstream.java"
    val b = "a/src/main/scala/a/Downstream.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a;
           |public class Upstream {
           |  public Upstream(int i) { }
           |  public Upstream(int i, int j) { }
           |  public static class Upstream2 {
           |    public int magic() { return 42; }
           |  }
           |}
           |/$b
           |package a
           |public class Downstream {
           |  public static void main() {
           |    Upstream a = new Upstream(1);
           |    new Upstream(1, 2);
           |    new Upstream.Upstream2().magic();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      // usages as type, term qualifier, and constructor
      _ <- server.assertReferencesSubquery(
        a,
        "class Upstr@@eam",
        """|a/src/main/scala/a/Downstream.java:4:5: reference
           |    Upstream a = new Upstream(1);
           |    ^^^^^^^^
           |a/src/main/scala/a/Downstream.java:4:22: reference
           |    Upstream a = new Upstream(1);
           |                     ^^^^^^^^
           |a/src/main/scala/a/Downstream.java:5:9: reference
           |    new Upstream(1, 2);
           |        ^^^^^^^^
           |a/src/main/scala/a/Downstream.java:6:9: reference
           |    new Upstream.Upstream2().magic();
           |        ^^^^^^^^
           |a/src/main/scala/a/Upstream.java:2:14: reference
           |public class Upstream {
           |             ^^^^^^^^
           |""".stripMargin,
      )
      // references on a constructor show exact usages of that overload
      _ <- server.assertReferencesSubquery(
        a,
        "public Up@@stream(int i, int j)",
        """|a/src/main/scala/a/Downstream.java:5:9: reference
           |    new Upstream(1, 2);
           |        ^^^^^^^^
           |a/src/main/scala/a/Upstream.java:4:10: reference
           |  public Upstream(int i, int j) { }
           |         ^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertReferencesSubquery(
        a,
        "class Upstr@@eam2",
        """|a/src/main/scala/a/Downstream.java:6:18: reference
           |    new Upstream.Upstream2().magic();
           |                 ^^^^^^^^^
           |a/src/main/scala/a/Upstream.java:5:23: reference
           |  public static class Upstream2 {
           |                      ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("enum") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Fruit.java"
    val b = "a/src/main/scala/a/Downstream.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a;
           |public enum Fruit {
           |  Apple, Banana;
           |}
           |/$b
           |package a
           |public class Downstream {
           |  public static void main() {
           |    Fruit a = Fruit.Apple;
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "enum Fru@@it",
        // TODO: avoid the references under Apple and Banana. These are synthetic
        // references that shouldn't be stripped away by semanticdb-javac.
        """|a/src/main/scala/a/Downstream.java:4:5: reference
           |    Fruit a = Fruit.Apple;
           |    ^^^^^
           |a/src/main/scala/a/Downstream.java:4:15: reference
           |    Fruit a = Fruit.Apple;
           |              ^^^^^
           |a/src/main/scala/a/Fruit.java:2:13: reference
           |public enum Fruit {
           |            ^^^^^
           |a/src/main/scala/a/Fruit.java:3:3: reference
           |  Apple, Banana;
           |  ^^^^^
           |a/src/main/scala/a/Fruit.java:3:10: reference
           |  Apple, Banana;
           |         ^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertReferencesSubquery(
        a,
        "Ap@@ple",
        """|a/src/main/scala/a/Downstream.java:4:21: reference
           |    Fruit a = Fruit.Apple;
           |                    ^^^^^
           |a/src/main/scala/a/Fruit.java:3:3: reference
           |  Apple, Banana;
           |  ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("record") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Fruit.java"
    val b = "a/src/main/scala/a/Downstream.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a;
           |public record Point(int xx, int y) {
           |  public int sum() {
           |    return xx + y;
           |  }
           |}
           |/$b
           |package a
           |public class Downstream {
           |  public static int run() {
           |    Point a = new Point(1, 2);
           |    return a.xx() + a.y() + a.sum();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "record Po@@int",
        """|a/src/main/scala/a/Downstream.java:4:5: reference
           |    Point a = new Point(1, 2);
           |    ^^^^^
           |a/src/main/scala/a/Downstream.java:4:19: reference
           |    Point a = new Point(1, 2);
           |                  ^^^^^
           |a/src/main/scala/a/Fruit.java:2:15: reference
           |public record Point(int xx, int y) {
           |              ^^^^^
           |""".stripMargin,
      )

      _ <- server.assertReferencesSubquery(
        a,
        "record Point(int x@@x",
        """|a/src/main/scala/a/Downstream.java:5:14: reference
           |    return a.xx() + a.y() + a.sum();
           |             ^^
           |a/src/main/scala/a/Fruit.java:2:25: reference
           |public record Point(int xx, int y) {
           |                        ^^
           |a/src/main/scala/a/Fruit.java:4:12: reference
           |    return xx + y;
           |           ^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("local") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Upstream.java"
    val b = "a/src/main/scala/a/Downstream.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a;
           |public class Upstream {
           |  public int x;
           |  public void run() {
           |    x = 1;
           |    var x = 2;
           |    if (x > 1) {
           |      int x = 3;
           |      int y = x + x;
           |    }
           |    ++x;
           |    return x++;
           |  }
           |}
           |/$b
           |package a
           |public class Downstream {
           |  public static void main() {
           |    // same "local0" symbol as `var x = 2`
           |    Fruit fruit = new Fruit();
           |    fruit.run();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "var @@x",
        """|a/src/main/scala/a/Upstream.java:6:9: reference
           |    var x = 2;
           |        ^
           |a/src/main/scala/a/Upstream.java:7:9: reference
           |    if (x > 1) {
           |        ^
           |a/src/main/scala/a/Upstream.java:11:7: reference
           |    ++x;
           |      ^
           |a/src/main/scala/a/Upstream.java:12:12: reference
           |    return x++;
           |           ^
           |""".stripMargin,
      )
    } yield ()
  }

  // implementations (class, interface, method)
  testLSP("method-override") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Fruit.java"
    val b = "a/src/main/scala/a/Downstream.java"
    val c = "a/src/main/scala/a/Berry.java"
    val d = "a/src/main/scala/a/Car.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a;
           |public abstract class Fruit {
           |  public abstract String color();
           |  public static class Apple extends Fruit {
           |    @Override public String color() { return "red" }
           |  }
           |  public static class TropicalFruit extends Fruit {
           |    @Override public String color() { return "green" }
           |  }
           |  public static class Banana extends TropicalFruit {
           |    @Override public String color() { return "yellow" }
           |  }
           |}
           |/$c
           |package a;
           |public class Berry extends Fruit {
           |  @Override public String color() { return "blue" }
           |}
           |/$d
           |package a;
           |public abstract class Car {
           |  abstract public String color();
           |  final class Ferrari extends Car {
           |    @Override public String color() { return "red" }
           |  }
           |}
           |/$b
           |package a
           |import a.Fruit.*;
           |public class Downstream {
           |  public static void main() {
           |    Fruit fruit = new Apple();
           |    Apple apple = new Apple();
           |    Banana banana = new Banana();
           |    Berry berry = new Berry();
           |    fruit.color();
           |    apple.color();
           |    banana.color();
           |    berry.color();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "public String co@@lor() { return \"yellow\" }",
        """|a/src/main/scala/a/Downstream.java:9:11: reference
           |    fruit.color();
           |          ^^^^^
           |a/src/main/scala/a/Downstream.java:10:11: reference
           |    apple.color();
           |          ^^^^^
           |a/src/main/scala/a/Downstream.java:11:12: reference
           |    banana.color();
           |           ^^^^^
           |a/src/main/scala/a/Downstream.java:12:11: reference
           |    berry.color();
           |          ^^^^^
           |a/src/main/scala/a/Fruit.java:3:26: reference
           |  public abstract String color();
           |                         ^^^^^
           |a/src/main/scala/a/Fruit.java:5:29: reference
           |    @Override public String color() { return "red" }
           |                            ^^^^^
           |a/src/main/scala/a/Fruit.java:8:29: reference
           |    @Override public String color() { return "green" }
           |                            ^^^^^
           |a/src/main/scala/a/Fruit.java:11:29: reference
           |    @Override public String color() { return "yellow" }
           |                            ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("class-extends") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Animal.java"
    val b = "a/src/main/scala/a/Mammal.java"
    val c = "a/src/main/scala/b/Animal.java"
    val d = "b/src/main/scala/b/Zoo.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a;
           |public class Animal {
           |  public String color() { return "blue" }
           |}
           |/$b
           |package a;
           |public class Mammal extends Animal {
           |  @Override public String color() { return "blue" }
           |  public static class Dog extends Mammal { @Override public String color() { return "brown" } }
           |}
           |/$c
           |package b;
           |public class Animal {
           |  public String color() { 
           |    var animal = new a.Animal();
           |    return animal.color();
           |  }
           |}
           |/$d
           |package b;
           |public interface Zoo<T> {}
           |public class DogZoo implements Zoo<a.Mammal.Dog> {}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "class Ani@@mal",
        """|a/src/main/scala/a/Animal.java:2:14: reference
           |public class Animal {
           |             ^^^^^^
           |a/src/main/scala/a/Mammal.java:2:29: reference
           |public class Mammal extends Animal {
           |                            ^^^^^^
           |a/src/main/scala/b/Animal.java:4:24: reference
           |    var animal = new a.Animal();
           |                       ^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertReferencesSubquery(
        b,
        "class Do@@g",
        """|a/src/main/scala/a/Mammal.java:4:23: reference
           |  public static class Dog extends Mammal { @Override public String color() { return "brown" } }
           |                      ^^^
           |b/src/main/scala/b/Zoo.java:3:45: reference
           |public class DogZoo implements Zoo<a.Mammal.Dog> {}
           |                                            ^^^
           |""".stripMargin,
      )
    } yield ()
  }

}
