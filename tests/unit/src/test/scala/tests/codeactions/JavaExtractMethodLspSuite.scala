package tests.codeactions

import scala.meta.internal.metals.codeactions.JavaExtractMethodCodeAction

class JavaExtractMethodLspSuite
    extends BaseCodeActionLspSuite("java-extract-method") {

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  check(
    "simple-expr",
    """|package a;
       |
       |public class Example {
       |  int method(int i) {
       |    return i + 1;
       |  }
       |
       |  void main() {
       |    int a = <<123 + method(4)>>;
       |  }
       |}
       |""".stripMargin,
    s"""|${JavaExtractMethodCodeAction.title("main")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  int method(int i) {
       |    return i + 1;
       |  }
       |
       |  private int newMethod() {
       |    return 123 + method(4);
       |  }
       |
       |  void main() {
       |    int a = newMethod();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "with-return",
    """|package a;
       |
       |public class Example {
       |  void foo() {
       |    int c = 1;
       |    <<int b = 2;
       |    int x = b + 10;>>
       |    System.out.println(x);
       |  }
       |}
       |""".stripMargin,
    s"""|${JavaExtractMethodCodeAction.title("foo")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private int newMethod() {
       |    int b = 2;
       |    int x = b + 10;
       |    return x;
       |  }
       |
       |  void foo() {
       |    int c = 1;
       |    int x = newMethod();
       |    System.out.println(x);
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "auto-import",
    """|package a;
       |
       |public class Example {
       |  void foo() {
       |    <<java.util.UUID.randomUUID();>>
       |  }
       |}
       |""".stripMargin,
    s"""|${JavaExtractMethodCodeAction.title("foo")}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.UUID;
       |
       |public class Example {
       |  private UUID newMethod() {
       |    return java.util.UUID.randomUUID();
       |  }
       |
       |  void foo() {
       |    newMethod();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )
}
