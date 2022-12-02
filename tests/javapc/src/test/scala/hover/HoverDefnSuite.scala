package hover

import java.net.URI

import tests.pc.BaseJavaHoverSuite

class HoverDefnSuite extends BaseJavaHoverSuite {
  check(
    "int value",
    """
      |class A {
      |    public static int NUMBER = 42;
      |
      |    public static void main(String args[]){
      |        int x = NU@@MBER;
      |    }
      |}
      |""".stripMargin,
    """
      |public static int NUMBER
      |""".stripMargin.javaHover,
  )

  check(
    "test.clazz",
    """
      |import java.util.List;
      |
      |class A {
      |  public static void main(String args[]){
      |    List<Integer> x = List.of(1);
      |    @@x;
      |  }
      |}
      |""".stripMargin,
    """|java.util.List<java.lang.Integer> x
       |""".stripMargin.javaHover,
  )

  check(
    "var assigment",
    """
      |import java.util.List;
      |
      |class A {
      |  public static void main(String args[]){
      |    var @@x = List.of(1);
      |  }
      |}
      |""".stripMargin,
    """|java.util.List<java.lang.Integer> x
       |""".stripMargin.javaHover,
  )

  check(
    "class definition",
    """
      |public class @@A {
      |  public static void main(String args[]){
      |  }
      |}
      |""".stripMargin,
    "public class class_definition.A".javaHover,
  )

  check(
    "class definition with inheritance",
    """
      |interface Foo {}
      |
      |interface Bar {}
      |
      |abstract class Baz {}
      |
      |public class @@A extends Baz implements Foo, Bar {
      |  public static void main(String args[]){
      |  }
      |}
      |""".stripMargin,
    "public class class_definition_with_inheritance.A extends class_definition_with_inheritance.Baz implements class_definition_with_inheritance.Foo, class_definition_with_inheritance.Bar".javaHover,
  )

  check(
    "function",
    """
      |class A {
      |  private static int foo() {
      |     return 42;
      |  }
      |
      |  public static void main(String args[]){
      |    fo@@o();
      |  }
      |}
      |""".stripMargin,
    "private static int foo()".javaHover,
  )

  check(
    "function with arguments",
    """
      |import java.util.List;
      |
      |class A {
      |  static int foo(int x, String s, List<Object> l) {
      |     return 42;
      |  }
      |
      |  public static void main(String args[]){
      |    fo@@o(1, "str", List.of());
      |  }
      |}
      |""".stripMargin,
    "static int foo(int x, java.lang.String s, java.util.List<java.lang.Object> l)".javaHover,
  )

  check(
    "function with throws",
    """
      |import java.util.List;
      |
      |class A {
      |  static int foo() throws RuntimeException {
      |     return 42;
      |  }
      |
      |  public static void main(String args[]){
      |    fo@@o();
      |  }
      |}
      |""".stripMargin,
    "static int foo() throws java.lang.RuntimeException".javaHover,
  )

  check(
    "function with vararg",
    """
      |class A {
      |  static int foo(int... a) {
      |     return 42;
      |  }
      |
      |  public static void main(String args[]){
      |    fo@@o(1, 2);
      |  }
      |}
      |""".stripMargin,
    "static int foo(int[] a)".javaHover, // todo investigate
  )

  check(
    "jar uri",
    """
      |class A {
      |    public static int NUMBER = 42;
      |
      |    public static void main(String args[]){
      |        int x = NU@@MBER;
      |    }
      |}
      |""".stripMargin,
    """
      |public static int NUMBER
      |""".stripMargin.javaHover,
    uri = URI.create("jar:file:/path/sample.jar!/src/main/java/A.java"),
  )
}
