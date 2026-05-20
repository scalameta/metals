package definition

import coursierapi.Dependency
import tests.pc.BaseJavaDefinitionSuite

class DefinitionSuite extends BaseJavaDefinitionSuite {

  override protected def extraDependencies: Seq[Dependency] = List(
    Dependency.of("com.google.guava", "guava", "31.1-jre")
  )

  check(
    "within-file",
    """
      |import java.util.Arrays;
      |import java.util.List;
      |class A {
      |    public static List<String> foo1 = Arrays.asList("blah");
      |    public static List<String> foo = fo@@o1
      |}
      |""".stripMargin,
    """|Definition.java:6:32: info: definition
       |    public static List<String> foo1 = Arrays.asList("blah");
       |                               ^^^^
       |""".stripMargin,
  )

  check(
    "to-dependency",
    """
      |import com.google.common.collect.Range;
      |class A {
      |    public static Range<Integer> foo = Range.cl@@osed(1, 10);
      |}
      |""".stripMargin,
    "com/google/common/collect/Range#closed(). Range.java",
  )

  check(
    "to-jdk-method",
    """
      |import java.util.Arrays;
      |import java.util.List;
      |class A {
      |    public static List<String> foo = Arrays.as@@List("blah");
      |}
      |""".stripMargin,
    "java/util/Arrays#asList(). Arrays.java",
  )

  check(
    "jdk-inner-class",
    """
      |class A {
      |    public static Object foo = java.util.Map.Entry.copy@@Of()
      |}
      |""".stripMargin,
    "java/util/Map#Entry#copyOf(). Map.java",
  )

  check(
    "ambiguous-sourcepath",
    """
      |class A {
      |    public static void valueOf(String s) {}
      |    public static void valueOf(int i) {}
      |    public static Object foo = A.va@@lueOf()
      |}
      |""".stripMargin,
    """|Definition.java:4:24: info: definition
       |    public static void valueOf(String s) {}
       |                       ^^^^^^^
       |Definition.java:5:24: info: definition
       |    public static void valueOf(int i) {}
       |                       ^^^^^^^
       |""".stripMargin,
  )

  check(
    "ambiguous-field-sourcepath",
    """
      |class A {
      |    public static void valueOf(String s) {}
      |    public static void valueOf(int i) {}
      |    public static Object foo = A.va@@lueOf
      |}
      |""".stripMargin,
    """|Definition.java:4:24: info: definition
       |    public static void valueOf(String s) {}
       |                       ^^^^^^^
       |Definition.java:5:24: info: definition
       |    public static void valueOf(int i) {}
       |                       ^^^^^^^
       |""".stripMargin,
  )

  check(
    "ambiguous-classpath",
    """
      |class A {
      |    public static Object foo = String.copyValue@@Of()
      |}
      |""".stripMargin,
    """|java/lang/String#copyValueOf(). String.java
       |java/lang/String#copyValueOf(+1). String.java
       |""".stripMargin,
  )

  check(
    "already-method-definition",
    """
      |class A {
      |    public String fo@@o() { return "message"; }
      |}
      |""".stripMargin,
    """|Definition.java:4:19: info: definition
       |    public String foo() { return "message"; }
       |                  ^^^
       |""".stripMargin,
  )

  check(
    "already-field-definition",
    """
      |class A {
      |    public static Object fo@@o = "message";
      |}
      |""".stripMargin,
    """|Definition.java:4:26: info: definition
       |    public static Object foo = "message";
       |                         ^^^
       |""".stripMargin,
  )

  check(
    "basic",
    """|class A {
       |    public static int NUMBER = 42;
       |
       |    public static void main(String args[]){
       |        int x = NU@@MBER;
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:23: info: definition
       |    public static int NUMBER = 42;
       |                      ^^^^^^
       |""".stripMargin,
  )

  check(
    "local-variable",
    """|class A {
       |    public static void main(String args[]){
       |        int x = 42;
       |        int y = @@x;
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:4:13: info: definition
       |        int x = 42;
       |            ^
       |""".stripMargin,
  )

  check(
    "method",
    """|class A {
       |    private static int foo() {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      fo@@o();
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:24: info: definition
       |    private static int foo() {
       |                       ^^^
       |""".stripMargin,
  )

  check(
    "method-with-args",
    """|import java.util.List;
       |
       |class A {
       |    static int foo(int x, String s) {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      fo@@o(1, "str");
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:5:16: info: definition
       |    static int foo(int x, String s) {
       |               ^^^
       |""".stripMargin,
  )

  check(
    "class-reference",
    """|class Foo {
       |    public int value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Fo@@o f = new Foo();
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:2:7: info: definition
       |class Foo {
       |      ^^^
       |""".stripMargin,
  )

  check(
    "new-instance",
    """|class Foo {
       |    public int value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Foo f = new Fo@@o();
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:2:7: info: definition
       |class Foo {
       |      ^^^
       |""".stripMargin,
  )

  check(
    "field-access",
    """|class Foo {
       |    public int value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.val@@ue;
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:16: info: definition
       |    public int value = 42;
       |               ^^^^^""".stripMargin,
  )

  check(
    "parameter",
    """|class A {
       |    static void foo(int x) {
       |       System.out.println(@@x);
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:25: info: definition
       |    static void foo(int x) {
       |                        ^
       |""".stripMargin,
  )

  check(
    "import",
    """|import java.util.@@List;
       |
       |class A {
       |    public static void main(String args[]){
       |      List<Integer> x = List.of(1);
       |    }
       |}
       |""".stripMargin,
    "java/util/List# List.java",
  )

  check(
    "extends",
    """|interface Foo {}
       |
       |class A implements Fo@@o {
       |}
       |""".stripMargin,
    """|Definition.java:2:11: info: definition
       |interface Foo {}
       |          ^^^
       |""".stripMargin,
  )

  check(
    "constructor-parameter",
    """|class Foo {
       |    private int value;
       |    public Foo(int value) {
       |        this.val@@ue = value;
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:17: info: definition
       |    private int value;
       |                ^^^^^
       |""".stripMargin,
  )

  check(
    "this-reference",
    """|class Foo {
       |    private int value;
       |    public void setValue(int value) {
       |        this.val@@ue = value;
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:17: info: definition
       |    private int value;
       |                ^^^^^
       |""".stripMargin,
  )

  check(
    "static-import",
    """|import static java.lang.Math.max;
       |
       |class A {
       |    public static void main(String args[]){
       |      int x = m@@ax(1, 2);
       |    }
       |}
       |""".stripMargin,
    "java/lang/Math#max(). Math.java",
  )

  check(
    "enum-constant",
    """|enum Color {
       |    RED, GREEN, BLUE
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Color c = Color.R@@ED;
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:5: info: definition
       |    RED, GREEN, BLUE
       |    ^^^
       |""".stripMargin,
  )

  check(
    "method-overload",
    """|class A {
       |    static void foo(int x) {}
       |    static void foo(String s) {}
       |
       |    public static void main(String args[]){
       |      fo@@o(42);
       |    }
       |}
       |""".stripMargin,
    """|Definition.java:3:17: info: definition
       |    static void foo(int x) {}
       |                ^^^
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
    "",
  )

  check(
    "static-import-site-import",
    """|import static java.lang.Math.m@@ax;
       |
       |class A {
       |    public static void main(String args[]){
       |      int x = max(1, 2);
       |    }
       |}
       |""".stripMargin,
    "java/lang/Math#max(). Math.java",
  )

}
