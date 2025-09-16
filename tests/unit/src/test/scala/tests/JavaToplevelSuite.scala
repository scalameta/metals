package tests

class JavaToplevelSuite extends BaseToplevelSuite {

  override def filename: String = "Test.java"
  override def allowedModes: Set[Mode] = Set(Toplevel, ToplevelWithInner, All)

  check(
    "base",
    s"""|package sample.pkg;
        |
        |import java.util.regex.PatternSyntaxException;
        |  /** First multi
        |   *
        |   */
        |public class Abc {
        |  public static void main(String[] args) {
        |    System.out.println("asdsad"); 
        |    // comment
        |  }
        |  public static void foo(int a, int, b) {
        |  }
        |}
        |
        |enum Enum {
        |  First
        |  Second
        |}
        |
        |
        |""".stripMargin,
    List("sample/pkg/Abc#", "sample/pkg/Enum#"),
  )

  check(
    "dot-class-anno",
    """|package dot.clz;
       |
       |@ClassSubstitution(String.class)
       |public class Abc {
       |
       |}
       |""".stripMargin,
    List("dot/clz/Abc#"),
  )

  check(
    "record-package",
    """|package dot.record;
       |
       |public class Abc {
       |
       |}
       |""".stripMargin,
    List("dot/record/Abc#"),
  )

  check(
    "enum-package",
    """|package dot.enum;
       |
       |public class Abc {
       |
       |}
       |""".stripMargin,
    List("dot/enum/Abc#"),
  )

  check(
    "broken",
    """|package dot.enum;
       |
       |public class Abc {
       |  public static final String REFERRER_ORIGIN_HOST = "audit.example.org.apache.hadoop.shaded.org.;
       |}
       |""".stripMargin,
    List("dot/enum/Abc#"),
  )

  check(
    "extends",
    """|package dot.example;
       |
       |public class JavaClass extends Exception {
       |
       |    private JavaClass() {
       |
       |    }
       |    public JavaClass(int d) {
       |        this.d = d;
       |    }
       |
       |    public static void a() {
       |    }
       |
       |    public int b() {
       |        return 1;
       |    }
       |
       |    public static int c = 2;
       |    public int d = 2;
       |
       |    public class InnerClass {
       |        public int b() {
       |            return 1;
       |        }
       |
       |        public int d = 2;
       |    }
       |
       |    public static class InnerStaticClass implements InnerInterface {
       |        public static void a() {
       |        }
       |
       |        public int b() {
       |            return 1;
       |        }
       |
       |        public static int c = 2;
       |        public int d = 2;
       |    }
       |
       |    public static interface InnerInterface {
       |        public static void a() {
       |        }
       |
       |        public int b();
       |    }
       |
       |    public String publicName() {
       |        return "name";
       |    }
       |
       |    // Weird formatting
       |    @Override
       |    public String
       |    toString() {
       |        return "";
       |    }
       |}
       |""".stripMargin,
    List("dot/", "dot/example/", "dot/example/JavaClass# -> Exception",
      "dot/example/JavaClass#InnerClass#",
      "dot/example/JavaClass#InnerInterface#",
      "dot/example/JavaClass#InnerStaticClass# -> InnerInterface"),
    mode = ToplevelWithInner,
  )

  check(
    "implements",
    """|package example;
       |
       |public class ExampleClass {
       |  public static interface SomeInterface<T, E> { }
       |
       |  public static interface SomeOtherInterface { }
       |
       |  public static class SomeClass extends SomeAbstractClass implements SomeInterface<Integer, Integer>, SomeOtherInterface {
       |    public static class InnerClass implements SomeOtherInterface { }
       |  }
       |
       |  public static abstract class SomeAbstractClass { }
       |}
       |""".stripMargin,
    List(
      "example/", "example/ExampleClass#",
      "example/ExampleClass#SomeClass# -> SomeAbstractClass, SomeInterface, SomeOtherInterface",
      "example/ExampleClass#SomeClass#InnerClass# -> SomeOtherInterface",
      "example/ExampleClass#SomeAbstractClass#",
      "example/ExampleClass#SomeInterface#",
      "example/ExampleClass#SomeOtherInterface#",
    ),
    mode = ToplevelWithInner,
  )

  check(
    "i6390",
    """|
       |<#include "/@includes/license.ftl" />
       |
       |package org.apache;
       |
       |<#include "/@includes/vv_imports.ftl" />
       |
       |@SuppressWarnings("unused")
       |public interface BaseReader extends Positionable{
       |}
       |""".stripMargin,
    List("""|org/
            |org/apache/
            |org/apache/BaseReader# -> Positionable
            |""".stripMargin),
    mode = ToplevelWithInner,
  )

  // The tests below are examples of where the Java toplevel indexer falls short.
  check(
    "qualified-extends".fail.pending(
      "Better Java support"
    ),
    """|
       |package org.apache;
       |
       |public interface Range extends java.util.Positionable<Integer> {
       |}
       |""".stripMargin,
    List("""|org/
            |org/apache/
            |org/apache/Range# -> java.util.Positionable
            |""".stripMargin),
    mode = ToplevelWithInner,
  )

  check(
    "fields-and-methods".fail.pending(
      "Better Java support"
    ),
    """|
       |package org.apache;
       |
       |public class Range {
       |  public int start;
       |  public int end;
       |  public int length() {
       |    return end - start;
       |  }
       |}
       |""".stripMargin,
    List("""|org/
            |org/apache/
            |org/apache/Range#
            |org/apache/Range#start.
            |org/apache/Range#end.
            |org/apache/Range#length().
            |""".stripMargin),
    mode = All,
  )

}
