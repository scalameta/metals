package tests

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.internal.mtags.Mtags

import munit._

class JavaToplevelSuite extends BaseSuite {

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
    s"""|sample/pkg/Abc#
        |sample/pkg/Enum#
        |""".stripMargin,
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
    s"""|dot/clz/Abc#
        |""".stripMargin,
  )

  check(
    "record-package",
    """|package dot.record;
       |
       |public class Abc {
       |
       |}
       |""".stripMargin,
    s"""|dot/record/Abc#
        |""".stripMargin,
  )

  check(
    "enum-package",
    """|package dot.enum;
       |
       |public class Abc {
       |
       |}
       |""".stripMargin,
    s"""|dot/enum/Abc#
        |""".stripMargin,
  )

  def check(
      name: TestOptions,
      code: String,
      expected: String,
  )(implicit loc: Location) {
    test(name) {
      val input = Input.VirtualFile("Test.java", code)
      val obtained =
        Mtags.topLevelSymbols(input, dialects.Scala213)

      assertNoDiff(
        obtained.sorted.mkString("\n"),
        expected,
      )
    }
  }
}
