package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionPackagePrivateVisibilitySuite extends BaseJavaCompletionSuite {
  check(
    "ok",
    """package example;
      |
      |public class Example1 {
      |  void visibilityPackagePrivate() {}
      |}
      |class Example2 {
      |  void run() {
      |    new Example1().visibility@@
      |  }
      |}
      |""".stripMargin,
    """|visibilityPackagePrivate()
       |""".stripMargin,
  )

  check(
    "ok-static",
    """package example;
      |
      |public class Example1 {
      |  staticvoid visibilityPackagePrivate2() {}
      |}
      |class Example2 {
      |  void run() {
      |    Example1.visibility@@
      |  }
      |}
      |""".stripMargin,
    """|visibilityPackagePrivate2()
       |""".stripMargin,
  )

  check(
    "not-ok",
    """package example;
      |
      |public class Example1 {
      |  void run() {
      |    String.valueOfCode@@
      |  }
      |}
      |""".stripMargin,
    "",
  )

  check(
    "not-ok-static",
    """package example;
      |
      |public class Example1 {
      |  void run() {
      |    var cf = new java.util.concurrent.CompletableFuture<>();
      |    cf.completeNul@@
      |  }
      |}
      |""".stripMargin,
    "",
  )

}
