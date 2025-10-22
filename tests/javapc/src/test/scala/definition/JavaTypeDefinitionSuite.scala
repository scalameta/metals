package definition

import tests.pc.BaseJavaDefinitionSuite

class JavaTypeDefinitionSuite extends BaseJavaDefinitionSuite {

  override def isTypeDefinitionSuite: Boolean = true

  check(
    "basic",
    """|class A {
       |    public static Integer NUMBER = 42;
       |
       |    public static void main(String args[]){
       |        Integer x = NU/*java/lang/Integer# Integer.java*/@@MBER;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "local-variable",
    """|
       |class A {
       |    public static void main(String args[]){
       |        String x = 42;
       |        String y = /*java/lang/String# String.java*/@@x;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method",
    """|class A {
       |    private static Double foo() {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      fo/*java/lang/Double# Double.java*/@@o();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method-with-args",
    """|import java.util.List;
       |
       |class A {
       |    static Float foo(int x, String s) {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      fo/*java/lang/Float# Float.java*/@@o(1, "str");
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "class-reference",
    """|class <<Foo>> {
       |    public int value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Fo@@o f = new Foo();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "new-instance",
    """|class <<Foo>> {
       |    public int value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Foo f = new Fo@@o();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "field-access",
    """|class Foo {
       |    public Double value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.val/*java/lang/Double# Double.java*/@@ue;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "parameter",
    """|class A {
       |    static void foo(String x) {
       |       System.out.println(/*java/lang/String# String.java*/@@x);
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "import",
    """|import java.util./*java/util/List# List.java*/@@List;
       |
       |class A {
       |    public static void main(String args[]){
       |      List<Integer> x = List.of(1);
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "extends",
    """|interface <<Foo>> {}
       |
       |class A implements Fo@@o {
       |}
       |""".stripMargin,
  )

  check(
    "constructor-parameter",
    """|class Foo {
       |    private Double value;
       |    public Foo(Double value) {
       |        this.val/*java/lang/Double# Double.java*/@@ue = value;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "this-reference",
    """|class Foo {
       |    private Double value;
       |    public void setValue(int value) {
       |        this.val/*java/lang/Double# Double.java*/@@ue = value;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "static-import",
    """|import static java.lang.Math.max;
       |
       |class A {
       |    public static void main(String args[]){
       |      int x = m/*java/lang/Math#max(). Math.java*/@@ax(1, 2);
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "enum-constant",
    """|enum <<Color>> {
       |    RED, GREEN, BLUE
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Color c = Color.R@@ED;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method-overload",
    """|class A {
       |    static Integer foo(int x) {
       |       return 42;
       |    }
       |    static String foo(String s) {
       |       return "42";
       |    }
       |
       |    public static void main(String args[]){
       |      fo/*java/lang/Integer# Integer.java*/@@o(42);
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "package",
    """|class A {
       |    public static void main(String args[]){
       |      int x = ja@@va.lang.Math.max(1, 2);
       |    }
       |}
       |""".stripMargin,
  )
}
