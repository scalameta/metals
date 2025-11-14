package pc

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

}
