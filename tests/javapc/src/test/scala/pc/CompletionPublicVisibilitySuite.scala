package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionPublicVisibilitySuite extends BaseJavaCompletionSuite {

  check(
    "static-only",
    """package example;
      |
      |public class Cafe {
      |  public String visibilityInstanceMethod() {
      |    return "banana";
      |  }
      |
      |  public static String visibilityStaticMethod() {
      |    return "coffee";
      |  }
      |
      |  public static void main(String[] args) {
      |    System.out.println(visibility@@());
      |  }
      |}
      |""".stripMargin,
    """
      |visibilityStaticMethod()
      |""".stripMargin,
    filterItem = item => item.getLabel().contains("visibility"),
  )

  check(
    "static-and-instance",
    """package example;
      |
      |public class Cafe {
      |  public String visibilityInstanceMethod() {
      |    return "banana";
      |  }
      |
      |  public static String visibilityStaticMethod() {
      |    return "coffee";
      |  }
      |
      |  public void instanceMethod() {
      |    visibility@@
      |  }
      |}
      |""".stripMargin,
    """
      |visibilityInstanceMethod()
      |visibilityStaticMethod()
      |""".stripMargin,
    filterItem = item => item.getLabel().contains("visibility"),
  )

}
