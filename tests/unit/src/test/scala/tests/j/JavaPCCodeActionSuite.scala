package tests.j

import scala.meta.internal.metals.codeactions.GenerateDefaultConstructor
import scala.meta.internal.metals.codeactions.ImportMissingSymbol

class JavaPCCodeActionSuite extends BaseJavaPCSuite("java-pc-code-action") {

  testLSP("generate-default-constructor") {
    cleanWorkspace()
    val path = "a/src/main/java/a/Example.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a;
            |
            |public class Example {
            |  private String name;
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      codeActions <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public class <<Example>> {
            |  private String name;
            |}
            |""".stripMargin,
        s"""|${GenerateDefaultConstructor.title("Example")}
            |""".stripMargin,
        Nil,
      )
      _ <- client.applyCodeAction(0, codeActions, server)
      _ = assertNoDiff(
        server.bufferContents(path),
        """|package a;
           |
           |public class Example {
           |  private String name;
           |
           |  public Example() {
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("generate-default-constructor-existing") {
    cleanWorkspace()
    val path = "a/src/main/java/a/Example.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a;
            |
            |public class Example {
            |  public Example() {
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public class <<Example>> {
            |  public Example() {
            |  }
            |}
            |""".stripMargin,
        "",
        Nil,
        filterAction =
          _.getTitle() == GenerateDefaultConstructor.title("Example"),
      )
    } yield ()
  }

  testLSP("generate-default-constructor-with-parameterized-constructor") {
    cleanWorkspace()
    val path = "a/src/main/java/a/Example.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a;
            |
            |public class Example {
            |  public Example(String name) {
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      codeActions <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public class <<Example>> {
            |  public Example(String name) {
            |  }
            |}
            |""".stripMargin,
        s"""|${GenerateDefaultConstructor.title("Example")}
            |""".stripMargin,
        Nil,
      )
      _ <- client.applyCodeAction(0, codeActions, server)
      _ = assertNoDiff(
        server.bufferContents(path),
        """|package a;
           |
           |public class Example {
           |  public Example() {
           |  }
           |
           |  public Example(String name) {
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("generate-default-constructor-interface") {
    cleanWorkspace()
    val path = "a/src/main/java/a/MyInterface.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a;
            |
            |public interface MyInterface {
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public interface <<MyInterface>> {
            |}
            |""".stripMargin,
        "",
        Nil,
        filterAction =
          _.getTitle() == GenerateDefaultConstructor.title("MyInterface"),
      )
    } yield ()
  }

  testLSP("generate-default-constructor-enum") {
    cleanWorkspace()
    val path = "a/src/main/java/a/MyEnum.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a;
            |
            |public enum MyEnum {
            |  A
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public enum <<MyEnum>> {
            |  A
            |}
            |""".stripMargin,
        "",
        Nil,
        filterAction =
          _.getTitle() == GenerateDefaultConstructor.title("MyEnum"),
      )
    } yield ()
  }

  testLSP("generate-default-constructor-abstract-class") {
    cleanWorkspace()
    val path = "a/src/main/java/a/Base.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a;
            |
            |public abstract class Base {
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      codeActions <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public abstract class <<Base>> {
            |}
            |""".stripMargin,
        s"""|${GenerateDefaultConstructor.title("Base")}
            |""".stripMargin,
        Nil,
      )
      _ <- client.applyCodeAction(0, codeActions, server)
      _ = assertNoDiff(
        server.bufferContents(path),
        """|package a;
           |
           |public abstract class Base {
           |  protected Base() {
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("generate-default-constructor-before-methods") {
    cleanWorkspace()
    val path = "a/src/main/java/a/Example.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a;
            |
            |public class Example {
            |  private String name;
            |
            |  public String name() {
            |    return name;
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      codeActions <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public class <<Example>> {
            |  private String name;
            |
            |  public String name() {
            |    return name;
            |  }
            |}
            |""".stripMargin,
        s"""|${GenerateDefaultConstructor.title("Example")}
            |""".stripMargin,
        Nil,
      )
      _ <- client.applyCodeAction(0, codeActions, server)
      _ = assertNoDiff(
        server.bufferContents(path),
        """|package a;
           |
           |public class Example {
           |  private String name;
           |
           |  public Example() {
           |  }
           |
           |  public String name() {
           |    return name;
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("external-dep") {
    cleanWorkspace()
    val path = "a/src/main/java/a/b/Example.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a.b;
            |
            |public class Example {
            |  public static Object visitor = new SimpleFileVisitor
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.didFocus(path)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/b/Example.java:4:38: error: cannot find symbol
           |  symbol:   class SimpleFileVisitor
           |  location: class a.b.Example
           |  public static Object visitor = new SimpleFileVisitor
           |                                     ^^^^^^^^^^^^^^^^^
           |a/src/main/java/a/b/Example.java:5:1: error: '(' or '[' expected
           |}
           |^
           |""".stripMargin,
      )
      _ <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public class Example {
            |  public static Object visitor = new <<SimpleFileVisitor>>
            |}
            |""".stripMargin,
        s"""|${ImportMissingSymbol.title("SimpleFileVisitor", "java.nio.file")}
            |""".stripMargin,
        Nil,
      )
    } yield ()
  }

  // Non-exact matches should not suggest imports - use completions for fuzzy search
  testLSP("non-exact") {
    cleanWorkspace()
    val path = "a/src/main/java/a/Example.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$path
            |package a;
            |
            |public class Example {
            |  public static SimpleFileVisit visitor = null;
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.didFocus(path)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/Example.java:4:17: error: cannot find symbol
           |  symbol:   class SimpleFileVisit
           |  location: class a.Example
           |  public static SimpleFileVisit visitor = null;
           |                ^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      // No code actions should be suggested for non-exact matches
      _ <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public class Example {
            |  public static <<SimpleFileVisit>> visitor = null;
            |}
            |""".stripMargin,
        "", // No suggestions for non-exact matches
        Nil,
      )
    } yield ()
  }

}
