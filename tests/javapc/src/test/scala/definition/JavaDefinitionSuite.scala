package definition

import tests.pc.BaseJavaDefinitionSuite

class JavaDefinitionSuite extends BaseJavaDefinitionSuite {

  override def isTypeDefinitionSuite: Boolean = false

  check(
    "basic",
    """|class A {
       |    public static int <<NUMBER>> = 42;
       |
       |    public static void main(String args[]){
       |        int x = NU@@MBER;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "local-variable",
    """|class A {
       |    public static void main(String args[]){
       |        int <<x>> = 42;
       |        int y = @@x;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method",
    """|class A {
       |    private static int <<foo>>() {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      fo@@o();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method-with-args",
    """|import java.util.List;
       |
       |class A {
       |    static int <<foo>>(int x, String s) {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      fo@@o(1, "str");
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
       |    public int <<value>> = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.val@@ue;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "parameter",
    """|class A {
       |    static void foo(int <<x>>) {
       |       System.out.println(@@x);
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
       |    private int <<value>>;
       |    public Foo(int value) {
       |        this.val@@ue = value;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "this-reference",
    """|class Foo {
       |    private int <<value>>;
       |    public void setValue(int value) {
       |        this.val@@ue = value;
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
    "static-import-site-import",
    """|import static java.lang.Math.m@@/*java/lang/Math#max(). Math.java*/ax;
       |
       |class A {
       |    public static void main(String args[]){
       |      int x = max(1, 2);
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "enum-constant",
    """|enum Color {
       |    <<RED>>, GREEN, BLUE
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
       |    static void <<foo>>(int x) {}
       |    static void foo(String s) {}
       |
       |    public static void main(String args[]){
       |      fo@@o(42);
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
