package tests.j

import scala.meta.internal.metals.codeactions.ImportMissingSymbol

class JavaPCCodeActionSuite extends BaseJavaPCSuite("java-pc-code-action") {

  testLSP("external-dep") {
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
            |  public static Object visitor = new SimpleFileVisitor
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.didFocus(path)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/Example.java:4:38: error: cannot find symbol
           |  symbol:   class SimpleFileVisitor
           |  location: class a.Example
           |  public static Object visitor = new SimpleFileVisitor
           |                                     ^^^^^^^^^^^^^^^^^
           |a/src/main/java/a/Example.java:5:1: error: '(' or '[' expected
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

  // NOTE(olafurpg): this is the current behavior, but I think it's better to
  // restrict this to exact matches.  Use completions if you want fuzzy search.
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
      _ <- server.assertCodeAction(
        path,
        s"""|package a;
            |
            |public class Example {
            |  public static <<SimpleFileVisit>> visitor = null;
            |}
            |""".stripMargin,
        // NOTE: it will insert the non-type name, but the label has the typo
        s"""|${ImportMissingSymbol.title("SimpleFileVisit", "java.nio.file")}
            |""".stripMargin,
        Nil,
      )
    } yield ()
  }

}
