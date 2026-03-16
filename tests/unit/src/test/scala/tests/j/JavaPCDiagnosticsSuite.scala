package tests.j

import scala.meta.internal.metals.MetalsEnrichments._

import coursierapi.Dependency
import coursierapi.Fetch
import org.eclipse.{lsp4j => l}

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

  // This demonstrates an unwanted behavior where we ignore the build
  // dependencies from BSP. We allow imports to any file from any target.
  test("target-cycles") {
    cleanWorkspace()
    val person = "a/src/main/java/a/Person.java"
    val main = "b/src/main/java/b/Main.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {},
            |  "b": {
            |    "dependsOn": ["a"]
            |  }
            |}
            |/$person
            |package a;
            |
            |public class Person {
            |  public String name;
            |  public int age;
            |  public Person(String name, int age) {
            |    this.name = name;
            |    this.age = age;
            |  }
            |  public static String greeting() {
            |    return b.Main.greet();
            |  }
            |}
            |/$main
            |package b;
            |
            |public class Main {
            |  public static String greet() {
            |    return new a.Person("Alice", 30).name;
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(person)
      _ <- server.didOpen(main)
      _ = assertNoDiagnostics()
      _ <- server.didChange(person)(
        _.replace("greet()", "greet2()")
      )
      _ <- server.didChange(main)(
        _.replace(".name", ".name2")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        // If we disallowed cycles, then it would report an error that b.Main
        // does not exist, not that `greet2` does not exist.
        """|a/src/main/java/a/Person.java:11:18: error: cannot find symbol
           |  symbol:   method greet2()
           |  location: class b.Main
           |    return b.Main.greet2();
           |                 ^^^^^^^
           |b/src/main/java/b/Main.java:5:37: error: cannot find symbol
           |  symbol:   variable name2
           |  location: class a.Person
           |    return new a.Person("Alice", 30).name2;
           |                                    ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("mbt-java-deps".tag(JavacSourcepath)) {
    cleanWorkspace()
    val mainFile = "Main.java"
    // Resolve the Guava dependency jars using Coursier
    val guavaDep = Dependency.of("com.google.guava", "guava", "33.5.0-jre")
    val fetched = Fetch
      .create()
      .withMainArtifacts()
      .addClassifiers("sources")
      .withDependencies(guavaDep)
      .fetch()
      .asScala
      .map(_.toPath)
      .toList
    val (sourceJars, classJars) =
      fetched.partition(_.getFileName.toString.endsWith("-sources.jar"))

    val mbtJson =
      s"""|{
          |  "dependencyModules": [
          |    {
          |      "id": "com.google.guava:guava:33.5.0-jre",
          |      "jar": "${classJars.head.toString}",
          |      "sources": "${sourceJars.head.toString}"
          |    }
          |  ]
          |}""".stripMargin
    val fileInput =
      """|package a;
         |
         |import com.google.common.collect.ImmutableList;
         |
         |public class Main {
         |  public static ImmutableList<String> names = ImmutableList.of("Alice", "Bob");
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""|/$mainFile
            |$fileInput
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen(mainFile)
      _ <- server.didFocus(mainFile)
      // Without the dependency, the import should fail
      _ = assert(
        client.workspaceDiagnostics.nonEmpty,
        "Expected diagnostics for missing com.google.common.collect dependency",
      )
      mbtJsonPath = workspace.resolve(".metals").resolve("mbt.json")
      _ = {
        workspace.resolve(".metals").toNIO.toFile.mkdirs()
        mbtJsonPath.writeText(mbtJson)
      }
      _ <- server.didChangeWatchedFiles(
        ".metals/mbt.json",
        l.FileChangeType.Created,
      )
      _ <- server.didClose(mainFile)
      _ <- server.didOpen(mainFile)
      // Trigger recompilation by making a change
      _ <- server.didChange(mainFile)(code => code + "\n// updated")
      _ <- server.assertHover(
        mainFile,
        fileInput.replace("ImmutableList.of", "Immutabl@@eList.of"),
        """|```java
           |public abstract class com.google.common.collect.ImmutableList<E> extends com.google.common.collect.ImmutableCollection<E> implements java.util.List<E>, java.util.RandomAccess
           |```
           |""".stripMargin,
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

}
