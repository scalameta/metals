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

  check(
    "after-statement",
    """
      |class Perfect {
      |  
      |  void println() {
      |    String name = "Tom";
      |    name.sub@@
      |    System.out.println("Perfect " + name);
      |  }
      |}
      |""".stripMargin,
    """|substring(int arg0)
       |substring(int arg0, int arg1)
       |subSequence(int arg0, int arg1)
       |""".stripMargin,
  )

  check(
    "same-line",
    """
      |class Perfect {
      |  
      |  void println() {
      |    String name = "Tom";
      |    name.sub@@  System.out.println("Perfect " + name);
      |  }
      |}
      |""".stripMargin,
    """|substring(int arg0)
       |substring(int arg0, int arg1)
       |subSequence(int arg0, int arg1)
       |""".stripMargin,
  )

  check(
    "inside-parens-no-space",
    """
      |class Perfect {
      |  
      |  void println() {
      |    String name = "Tom";
      |    System.out.println(name.sub@@);
      |  }
      |}
      |""".stripMargin,
    """|substring(int arg0)
       |substring(int arg0, int arg1)
       |subSequence(int arg0, int arg1)
       |""".stripMargin,
  )

  check(
    "inside-word",
    """
      |class Perfect {
      |  
      |  void println() {
      |    String name = "Tom";
      |    System.out.println(name.sub@@S );
      |  }
      |}
      |""".stripMargin,
    """|substring(int arg0)
       |substring(int arg0, int arg1)
       |subSequence(int arg0, int arg1)
       |""".stripMargin,
  )
}
