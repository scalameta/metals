package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionProtectedVisibilitySuite extends BaseJavaCompletionSuite {

  check(
    "protected-member",
    """
      |
      |import java.util.concurrent.CompletableFuture;
      |
      |class Perfect {
      |  
      |  void println() {
      |    CompletableFuture<?> cf = new CompletableFuture<>();
      |    cf.clon@@
      |  }
      |}
      |""".stripMargin,
    "",
  )

  check(
    "protected-static-nested-class",
    """
      |class Outer {
      |  class A {
      |    protected static int protectedField = 42;
      |    protected static void protectedMethod() {}
      |  }
      |  class B extends A {
      |    public static void foo() {
      |      prot@@
      |    }
      |  }
      |}
      |""".stripMargin,
    """
      |protectedField
      |protectedMethod()
      |""".stripMargin,
  )
}
