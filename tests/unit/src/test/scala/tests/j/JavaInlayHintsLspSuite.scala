package tests.j

import tests.TestInlayHints

/**
 * End-to-end tests for inlay hints on Java sources, which are backed by the
 * Java presentation compiler.
 */
class JavaInlayHintsLspSuite extends BaseJavaPCSuite("java-inlay-hints") {

  private val path = "a/src/main/java/a/Example.java"

  private def check(
      name: String,
      config: String,
      expected: String,
  ): Unit =
    testLSP(name) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""|/metals.json
              |{"a": {}}
              |/$path
              |${TestInlayHints.removeInlayHints(expected)}
              |""".stripMargin
        )
        _ <- server.didOpen(path)
        _ <- server.didChangeConfiguration(
          s"""|{"inlayHints": {
              |$config
              |}}
              |""".stripMargin
        )
        _ <- server.assertInlayHints(path, expected, workspace)
      } yield ()
    }

  check(
    "var-types-and-parameter-names",
    """|"inferredTypes": {"enable": true},
       |"namedParameters": {"enable": true}
       |""".stripMargin,
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  static int clamp(int value, int lower, int upper) { return value; }
       |  int run(List<String> names, int value) {
       |    var first/*: String<<java/lang/String#>>*/ = names.get(0);
       |    return clamp(value, /*lower: */0, /*upper: */10);
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "only-var-types",
    """|"inferredTypes": {"enable": true}
       |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  static int clamp(int value, int lower, int upper) { return value; }
       |  int run() {
       |    var size/*: int*/ = Integer.valueOf(1).intValue();
       |    return clamp(size, 0, 10);
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "only-parameter-names",
    """|"namedParameters": {"enable": true}
       |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  static int clamp(int value, int lower, int upper) { return value; }
       |  int run() {
       |    var size = Integer.valueOf(1).intValue();
       |    return clamp(/*value: */size, /*lower: */0, /*upper: */10);
       |  }
       |}
       |""".stripMargin,
  )
}
