package tests.j

import scala.meta.internal.metals.codeactions.ImportMissingSymbol

class JavaPCCodeActionSuite extends BaseJavaPCSuite("java-pc-code-action") {

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
