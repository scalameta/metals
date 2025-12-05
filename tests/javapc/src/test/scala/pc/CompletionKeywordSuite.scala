package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionKeywordSuite extends BaseJavaCompletionSuite {
  check(
    "super",
    """
      |
      |interface A {}
      |
      |class B implements A {
      |
      |    public static int foo() {
      |         sup@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |super
      |""".stripMargin,
  )

  check(
    "this",
    """
      |
      |interface A {}
      |
      |class B implements A {
      |
      |    public static int foo() {
      |         thi@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |this
      |""".stripMargin,
  )

  check(
    "package",
    """
      |pac@@
      |
      |interface A {}
      |""".stripMargin,
    """
      |package
      |""".stripMargin,
  )

  check(
    "end of file",
    """
      |pac@@""".stripMargin,
    """
      |package
      |""".stripMargin,
  )

  check(
    "extends",
    """
      |
      |class A {}
      |
      |class B ext@@ A {}
      |""".stripMargin,
    """
      |extends
      |""".stripMargin,
  )

  check(
    "no extends paren",
    """
      |
      |class A {
      |
      |    public static int foo() {
      |         return 42;
      |    }
      |
      |    public static void main() {
      |         foo() ext@@;
      |    }
      |
      |}
      |""".stripMargin,
    """|extObjectInputStream
       |""".stripMargin,
  )

  check(
    "protected interface",
    """
      |
      |protected inte@@ A {}
      |""".stripMargin,
    """
      |interface
      |""".stripMargin,
  )

  check(
    "new",
    """
      |class A {
      |
      |    public static int foo() {
      |         ne@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |new
      |""".stripMargin,
  )

  check(
    "return",
    """
      |class A {
      |
      |    public static int foo() {
      |         ret@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |return
      |""".stripMargin,
  )
}
