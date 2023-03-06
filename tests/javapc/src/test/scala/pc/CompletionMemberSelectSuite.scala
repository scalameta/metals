package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionMemberSelectSuite extends BaseJavaCompletionSuite {
  check(
    "static field member",
    """
      |class Simple{
      |    public static int NUMBER = 42;
      |
      |    public static void main(String args[]){
      |        new Simple().NU@@
      |    }
      |}
      |""".stripMargin,
    """
      |NUMBER
      |""".stripMargin,
  )

  check(
    "array member",
    """
      |class Simple{
      |    public static void main(String args[]){
      |        new int[42].le@@
      |    }
      |}
      |""".stripMargin,
    """
      |length
      |""".stripMargin,
  )

  check(
    "type variable",
    """
      |class Simple{
      |    public static int NUMBER = 42;
      |
      |    public static void test<T extends Simple>() {
      |        T.NUM@@
      |    }
      |}
      |""".stripMargin,
    """
      |NUMBER
      |""".stripMargin,
  )
}
