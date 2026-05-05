package tests

import scala.meta.inputs.Input
import scala.meta.internal.mtags.JavaMtags
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
}
