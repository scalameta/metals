package tests.j

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

class JavaPCDiagnosticsSuite extends BaseJavaPCSuite("java-pc-diagnostics") {

  test("no-errors") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Example.java
           |package a;
           |
           |public class Example {
           |  public static String message = "Hello, World!";
           |  public static String greet(String name) {
           |    return "Hello, " + name + "!";
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Example.java")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("simple-type-error") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/TypeErrors.java
           |package a;
           |
           |public class TypeErrors {
           |  public static int number = "not a number";
           |  public static String text = 42;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/TypeErrors.java")
      _ <- server.didFocus("a/src/main/java/a/TypeErrors.java")
      // diagnostics are sent asynchronously and we don't have a future to await here
      // so we wait until the diagnostics are available or 5 seconds have passed
      _ <- waitUntil(5.seconds)(client.workspaceDiagnostics.nonEmpty)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/TypeErrors.java:4:30: error: incompatible types: java.lang.String cannot be converted to int
           |  public static int number = "not a number";
           |                             ^^^^^^^^^^^^^^
           |a/src/main/java/a/TypeErrors.java:5:31: error: incompatible types: int cannot be converted to java.lang.String
           |  public static String text = 42;
           |                              ^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("introduce-type-error") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Dynamic.java
           |package a;
           |
           |public class Dynamic {
           |  public static int number = 42;
           |  public static String text = "hello";
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Dynamic.java")
      _ <- server.didFocus("a/src/main/java/a/Dynamic.java")
      _ = assertNoDiagnostics()
      // Introduce a type error by changing the string assignment to an int
      _ <- server.didChange("a/src/main/java/a/Dynamic.java")(
        _.replace(
          "public static String text = \"hello\"",
          "public static String text = 123",
        )
      )
      _ <- waitUntil(5.seconds)(client.workspaceDiagnostics.nonEmpty)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/Dynamic.java:5:31: error: incompatible types: int cannot be converted to java.lang.String
           |  public static String text = 123;
           |                              ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // this tests that the presentation compiler is able to load sources
  // that haven't been compiled yet, but are placed in the right directory structure
  test("multi-file") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Person.java
           |package a;
           |
           |public class Person {
           |  public String name;
           |  public int age;
           |  public Person(String name, int age) {
           |    this.name = name;
           |    this.age = age;
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static Person person = new Person("Alice", 30);
           |  public static String greet() {
           |    return "Hello, " + person.name + "!";
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace("30", "31")
      )
      // not good practice, but they arrive fully asynchronously and we don't have
      // a way to know when typechecking finished
      _ <- server.waitFor(2000)
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("fallback-compiler") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Person.java
           |package a;
           |
           |public class Person {
           |  public String name;
           |  public int age;
           |  public Person(String name, int age) {
           |    this.name = name;
           |    this.age = age;
           |  }
           |}
           |/whatever/Foo.java
           |package whatever;
           |
           |public class Foo {
           |  public static String greet() {
           |    return "Hello";
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Person.java")
      _ <- server.didChange("a/src/main/java/a/Person.java")(
        _.replace("name = name;", "name = name.length();")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/Person.java:7:28: error: incompatible types: int cannot be converted to java.lang.String
           |    this.name = name.length();
           |                           ^^
           |""".stripMargin,
      )
      _ <- server.didChange("a/src/main/java/a/Person.java")(
        // Fix the error so it doesn't appear in the assertion below
        _.replace("name = name.length();", "name = name;")
      )
      _ <- server.didOpen("whatever/Foo.java")
      _ <- server.didChange("whatever/Foo.java")(
        _.replace("\"Hello\"", "\"Hello, World!\".length()")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|whatever/Foo.java:5:34: error: incompatible types: int cannot be converted to java.lang.String
           |    return "Hello, World!".length();
           |                                 ^^
           |""".stripMargin,
      )
    } yield ()
  }

  private def waitUntil(timeout: Duration)(cond: => Boolean): Future[Unit] =
    Future {
      val deadline = System.nanoTime() + timeout.toNanos
      while (!cond && System.nanoTime() < deadline) {
        Thread.sleep(100)
      }
    }
}
