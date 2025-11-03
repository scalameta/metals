package tests.j

class GoogleJavaFormatLspSuite
    extends BaseJavaPCSuite("google-java-format-lsp") {

  testLSP("basic") {
    cleanWorkspace()
    val example = "a/src/main/java/a/Example.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$example
            |package a;
            |
            |import java.util.ArrayList;
            |import java.util.Map;
            |import java.util.HashMap;
            |
            |public class Example {
            |
            |
            |    public static Map<String, String> map = new HashMap<>();
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(example)
      _ <- server.formatting(example)
      _ = assertNoDiff(
        server.bufferContents(example),
        // Assert:
        // 1. Formatting was applied (indentation, spacing, etc.)
        // 2. Unused imports were removed
        // 3. Imports were reordered
        """|package a;
           |
           |import java.util.HashMap;
           |import java.util.Map;
           |
           |public class Example {
           |
           |  public static Map<String, String> map = new HashMap<>();
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}
