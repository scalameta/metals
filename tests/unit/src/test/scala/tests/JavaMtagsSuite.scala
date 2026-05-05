package tests

import scala.meta.inputs.Input
import scala.meta.internal.mtags.JavaMtags
import scala.meta.internal.mtags.JavadocParser
import scala.meta.internal.mtags.JavadocTag
import scala.meta.pc.reports.EmptyReportContext

/**
 * Tests that JavaMtags (tree-sitter based) correctly indexes Java source files,
 * including edge cases with annotations in type parameters that caused issues
 * with the previous QDox parser.
 */
class JavaMtagsSuite extends BaseSuite {

  implicit val rc: EmptyReportContext = new EmptyReportContext

  def check(
      name: String,
      code: String,
      expected: String,
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      val input = Input.VirtualFile("Test.java", code)
      val mtags = new JavaMtags(input, includeMembers = true)
      val doc = mtags.index()
      val obtained = doc.occurrences.map(_.symbol).mkString("\n")
      assertNoDiff(obtained, expected)
    }
  }

  check(
    "annotated-type-params",
    """|package com.example;
       |
       |import java.util.function.Function;
       |import java.util.stream.Collector;
       |
       |public class CollectorUtils {
       |  public static <T extends @Nullable Object, K, V>
       |      Collector<T, ?, ImmutableMap<K, V>> toImmutableMap(
       |          Function<? super T, ? extends K> keyFunction,
       |          Function<? super T, ? extends V> valueFunction) {
       |    return null;
       |  }
       |
       |  public <@NonNull T> T requireNonNull(@Nullable T obj, String message) {
       |    return obj;
       |  }
       |
       |  public void process(java.util.@Nullable List<String> items) {
       |  }
       |}
       |""".stripMargin,
    """|com/
       |com/example/
       |com/example/CollectorUtils#
       |com/example/CollectorUtils#requireNonNull().
       |com/example/CollectorUtils#process().
       |com/example/CollectorUtils#toImmutableMap().
       |""".stripMargin.trim,
  )

  check(
    "basic-class-with-members",
    """|package sample;
       |
       |public class Foo {
       |  public int bar;
       |  public static void main(String[] args) {}
       |  public Foo(int bar) { this.bar = bar; }
       |}
       |""".stripMargin,
    // methods are sorted static-last, then constructors, then fields
    """|sample/
       |sample/Foo#
       |sample/Foo#main().
       |sample/Foo#`<init>`().
       |sample/Foo#bar.
       |""".stripMargin.trim,
  )

  check(
    "enum-with-constants",
    """|package sample;
       |
       |public enum Color {
       |  RED,
       |  GREEN,
       |  BLUE;
       |
       |  public String display() { return name(); }
       |}
       |""".stripMargin,
    """|sample/
       |sample/Color#
       |sample/Color#RED.
       |sample/Color#GREEN.
       |sample/Color#BLUE.
       |sample/Color#display().
       |""".stripMargin.trim,
  )

  check(
    "interface-with-default-methods",
    """|package sample;
       |
       |public interface Processor<T> {
       |  void process(T item);
       |  default void processAll(java.util.List<T> items) {}
       |}
       |""".stripMargin,
    """|sample/
       |sample/Processor#
       |sample/Processor#process().
       |sample/Processor#processAll().
       |""".stripMargin.trim,
  )

  check(
    "varargs-parameter",
    """|package sample;
       |
       |public class VarArgs {
       |  public void log(String format, Object... args) {}
       |  public static void multiVarArgs(int first, String... rest) {}
       |}
       |""".stripMargin,
    """|sample/
       |sample/VarArgs#
       |sample/VarArgs#log().
       |sample/VarArgs#multiVarArgs().
       |""".stripMargin.trim,
  )

  check(
    "unicode-identifiers",
    """|package sample;
       |
       |public class Café {
       |  public int prénom;
       |  public String élève;
       |  public void calculé() {}
       |}
       |""".stripMargin,
    """|sample/
       |sample/Café#
       |sample/Café#calculé().
       |sample/Café#prénom.
       |sample/Café#élève.
       |""".stripMargin.trim,
  )

  check(
    "unicode-before-identifier",
    // Test that byte offset -> char offset conversion works when non-ASCII
    // characters precede an identifier on the same line
    """|package sample;
       |
       |public class UnicodeTest {
       |  public String greet = "éèê"; public int after;
       |}
       |""".stripMargin,
    """|sample/
       |sample/UnicodeTest#
       |sample/UnicodeTest#greet.
       |sample/UnicodeTest#after.
       |""".stripMargin.trim,
  )

  check(
    "error-recovery",
    // Tree-sitter produces a partial tree for malformed Java and recovers
    // as much as possible — even extracting names from malformed declarations
    """|package sample;
       |
       |public class Broken {
       |  public void valid() {}
       |  public void invalid( {}
       |  public void alsoValid() {}
       |}
       |""".stripMargin,
    """|sample/
       |sample/Broken#
       |sample/Broken#valid().
       |sample/Broken#invalid().
       |sample/Broken#alsoValid().
       |""".stripMargin.trim,
  )

  check(
    "empty-class",
    """|package sample;
       |
       |public class Empty {}
       |""".stripMargin,
    """|sample/
       |sample/Empty#
       |""".stripMargin.trim,
  )

  check(
    "nested-declarations",
    """|package sample;
       |
       |public class Outer {
       |  public int outerField;
       |  public void outerMethod() {}
       |
       |  public static class StaticInner {
       |    public String innerField;
       |    public void innerMethod() {}
       |
       |    public enum InnerEnum {
       |      A, B;
       |      public int value() { return ordinal(); }
       |    }
       |  }
       |
       |  public interface InnerInterface {
       |    void doWork();
       |    default void doDefault() {}
       |  }
       |
       |  private enum Status {
       |    ACTIVE,
       |    INACTIVE;
       |
       |    public boolean isActive() { return this == ACTIVE; }
       |  }
       |
       |  public record Point(int x, int y) {
       |    public double distance() { return Math.sqrt(x*x + y*y); }
       |  }
       |
       |  public @interface Marker {
       |    String value() default "";
       |  }
       |}
       |""".stripMargin,
    """|sample/
       |sample/Outer#
       |sample/Outer#StaticInner#
       |sample/Outer#StaticInner#InnerEnum#
       |sample/Outer#StaticInner#InnerEnum#A.
       |sample/Outer#StaticInner#InnerEnum#B.
       |sample/Outer#StaticInner#InnerEnum#value().
       |sample/Outer#StaticInner#innerMethod().
       |sample/Outer#StaticInner#innerField.
       |sample/Outer#InnerInterface#
       |sample/Outer#InnerInterface#doWork().
       |sample/Outer#InnerInterface#doDefault().
       |sample/Outer#Status#
       |sample/Outer#Status#ACTIVE.
       |sample/Outer#Status#INACTIVE.
       |sample/Outer#Status#isActive().
       |sample/Outer#Point#
       |sample/Outer#Point#distance().
       |sample/Outer#Marker#
       |sample/Outer#Marker#value().
       |sample/Outer#outerMethod().
       |sample/Outer#outerField.
       |""".stripMargin.trim,
  )

  check(
    "interface-constants",
    """|package sample;
       |
       |public interface Constants {
       |  int MAX_SIZE = 100;
       |  String DEFAULT_NAME = "test";
       |  void doSomething();
       |}
       |""".stripMargin,
    """|sample/
       |sample/Constants#
       |sample/Constants#doSomething().
       |sample/Constants#MAX_SIZE.
       |sample/Constants#DEFAULT_NAME.
       |""".stripMargin.trim,
  )

  check(
    "annotation-type-elements",
    """|package sample;
       |
       |public @interface MyAnnotation {
       |  String value();
       |  int count() default 0;
       |}
       |""".stripMargin,
    """|sample/
       |sample/MyAnnotation#
       |sample/MyAnnotation#value().
       |sample/MyAnnotation#count().
       |""".stripMargin.trim,
  )

  // --- JavadocParser tests ---

  test("javadoc-parser-basic") {
    val raw = """|/**
                 | * This is the body.
                 | * @param name the name
                 | * @param age the age
                 | * @return something
                 | */""".stripMargin
    val result = JavadocParser.parse(raw)
    assert(result.isDefined)
    val doc = result.get
    assertNoDiff(doc.body, "This is the body.")
    assertEquals(doc.tags.length, 3)
    assertEquals(doc.tags(0), JavadocTag("param", "name the name"))
    assertEquals(doc.tags(1), JavadocTag("param", "age the age"))
    assertEquals(doc.tags(2), JavadocTag("return", "something"))
  }

  test("javadoc-parser-empty-comment") {
    val raw = "/** */"
    val result = JavadocParser.parse(raw)
    assert(result.isDefined)
    val doc = result.get
    assertEquals(doc.body, "")
    assertEquals(doc.tags, Nil)
  }

  test("javadoc-parser-not-javadoc") {
    val raw = "/* regular block comment */"
    val result = JavadocParser.parse(raw)
    assertEquals(result, None)
  }

  test("javadoc-parser-null-input") {
    val result = JavadocParser.parse(null)
    assertEquals(result, None)
  }

  test("javadoc-parser-multiline-tag") {
    val raw = """|/**
                 | * Body text.
                 | * @param name the name of
                 | *        the thing being described
                 | * @return the result
                 | */""".stripMargin
    val result = JavadocParser.parse(raw)
    assert(result.isDefined)
    val doc = result.get
    assertNoDiff(doc.body, "Body text.")
    assertEquals(doc.tags.length, 2)
    assert(doc.tags(0).value.contains("the name of"))
    assertEquals(doc.tags(1), JavadocTag("return", "the result"))
  }

  test("javadoc-parser-body-only") {
    val raw = """|/**
                 | * Just a body with no tags.
                 | * Multiple lines.
                 | */""".stripMargin
    val result = JavadocParser.parse(raw)
    assert(result.isDefined)
    val doc = result.get
    assert(doc.body.contains("Just a body"))
    assert(doc.body.contains("Multiple lines"))
    assertEquals(doc.tags, Nil)
  }

  test("javadoc-parser-tags-only") {
    val raw = """|/**
                 | * @param x the x coord
                 | * @param y the y coord
                 | */""".stripMargin
    val result = JavadocParser.parse(raw)
    assert(result.isDefined)
    val doc = result.get
    assertEquals(doc.body, "")
    assertEquals(doc.tags.length, 2)
    assertEquals(doc.tagsByName("param").length, 2)
  }

  test("javadoc-parser-malformed-tag") {
    // Tag with no value
    val raw = """|/**
                 | * Body.
                 | * @deprecated
                 | */""".stripMargin
    val result = JavadocParser.parse(raw)
    assert(result.isDefined)
    val doc = result.get
    assertNoDiff(doc.body, "Body.")
    assertEquals(doc.tags.length, 1)
    assertEquals(doc.tags(0), JavadocTag("deprecated", ""))
  }
}
