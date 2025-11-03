package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionPrivateVisibilitySuite extends BaseJavaCompletionSuite {

  check(
    "basic",
    """
      |class A {
      |    private int privateField = 42;
      |    private void privateMethod() {}
      |    public void foo() {
      |         priv@@
      |    }
      |}
      |""".stripMargin,
    """
      |privateField
      |privateMethod()
      |""".stripMargin,
  )

  check(
    "basic-static",
    """
      |class A {
      |    private static int privateField = 42;
      |    private static void privateMethod() {}
      |    public static void foo() {
      |         priv@@
      |    }
      |}
      |""".stripMargin,
    """
      |privateField
      |privateMethod()
      |""".stripMargin,
  )

  check(
    "nested-class",
    """class Outer {
      |  class A {
      |    private int visibilityPrivateField = 42;
      |    private void visibilityPrivateMethod() {}
      |    public int visibilityPublicField = 10;
      |    public void visibilityPublicMethod() {}
      |  }
      |
      |  class B {
      |    public static void test() {
      |      A a = new A();
      |      a.visibility@@
      |    }
      |  }
      |}
      |""".stripMargin,
    """|visibilityPrivateField
       |visibilityPrivateMethod()
       |visibilityPublicField
       |visibilityPublicMethod()
       |""".stripMargin,
    filterItem = item => item.getLabel().contains("visibility"),
  )

  check(
    "nested-static-class",
    """package example;
      |
      |public class Foobar {
      |  private int visibilityPrivateField = 42;
      |  private void visibilityPrivateMethod() {}
      |  public int visibilityPublicField = 10;
      |  public void visibilityPublicMethod() {}
      |
      |  static class Other {
      |    public void other() {
      |      var f = new Foobar();
      |      f.visibility@@
      |    }
      |  }
      |}
      |""".stripMargin,
    // Same behavior as in a non-static nested class
    """|visibilityPrivateField
       |visibilityPrivateMethod()
       |visibilityPublicField
       |visibilityPublicMethod()
       |""".stripMargin,
    filterItem = item => item.getLabel().contains("visibility"),
  )

}
