package pc

import coursierapi.Dependency
import tests.pc.BaseJavaTypeDefinitionSuite

class TypeDefinitionSuite extends BaseJavaTypeDefinitionSuite {

  override protected def extraDependencies: Seq[Dependency] = List(
    Dependency.of("com.google.guava", "guava", "31.1-jre")
  )

  check(
    "within-file",
    """
      |import java.util.Arrays;
      |import java.util.List;
      |class A {
      |    public static A create() { return new A(); }j
      |    public static A foo = cre@@ate()
      |}
      |""".stripMargin,
    """|Definition.java:5:7: info: definition
       |class A {
       |      ^
       |""".stripMargin,
  )

  check(
    "within-file-to-external",
    """
      |import java.util.Arrays;
      |import java.util.List;
      |class A {
      |    public static List<String> foo1 = Arrays.asList("blah");
      |    public static List<String> foo = fo@@o1
      |}
      |""".stripMargin,
    "java/util/List# List.java",
  )

  check(
    "type-variable",
    """
      |import java.util.Arrays;
      |import java.util.List;
      |class A {
      |    public static <T extends CharSequence> int foo(T el) { return e@@l.length(); }
      |}
      |""".stripMargin,
    // Resolve to the upper bound of the type variable
    "java/lang/CharSequence# CharSequence.java",
  )

  check(
    "to-dependency",
    """
      |import com.google.common.collect.Range;
      |class A {
      |    public static Range<Integer> foo = Range.cl@@osed(1, 10);
      |}
      |""".stripMargin,
    // The type, not the "closed" method
    "com/google/common/collect/Range# Range.java",
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
    "java/util/List# List.java",
  )

  check(
    "jdk-inner-class",
    """
      |class A {
      |    public static Object foo = java.util.Map.Entry.copy@@Of()
      |}
      |""".stripMargin,
    "java/util/Map#Entry# Map.java",
  )

  check(
    "ambiguous-sourcepath",
    """
      |class A {
      |    public static String valueOf(String s) { return s;}
      |    public static Integer valueOf(int i) { return i; }
      |    public static Object foo = A.va@@lueOf()
      |}
      |""".stripMargin,
    // The union of the return types
    """|java/lang/String# String.java
       |java/lang/Integer# Integer.java
       |""".stripMargin,
  )

  check(
    "ambiguous-field-sourcepath",
    """
      |class A {
      |    public static String valueOf(String s) { return s;}
      |    public static Integer valueOf(int i) { return i; }
      |    public static Object foo = A.va@@lueOf
      |}
      |""".stripMargin,
    """|java/lang/String# String.java
       |java/lang/Integer# Integer.java
       |""".stripMargin,
  )

  check(
    "ambiguous-classpath",
    """
      |class A {
      |    public static Object foo = String.copyValue@@Of()
      |}
      |""".stripMargin,
    // Distinct result even if there are multiple candidate methods, as long as
    // they have the same return type.
    "java/lang/String# String.java",
  )
}
