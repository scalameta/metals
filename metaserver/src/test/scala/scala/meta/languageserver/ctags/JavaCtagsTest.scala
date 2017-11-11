package scala.meta.languageserver.ctags

import scala.meta.testkit.DiffAssertions
import org.scalatest.FunSuite

class JavaCtagsTest extends FunSuite with DiffAssertions {
  test("index java source") {
    val obtained = Ctags.index(
      "a.java",
      """package a.b;
        |interface A { String a(); }
        |class B { static void foo() { } }
        |""".stripMargin
    )
    assertNoDiff(
      obtained.syntax,
      """
        |Language:
        |Java
        |
        |Names:
        |[8..11): a.b <= _root_.a.
        |[8..11): a.b <= _root_.a.b.
        |[23..24): A <= _root_.a.b.A#
        |[34..35): a <= _root_.a.b.A#a.
        |[47..48): B <= _root_.a.b.B#
        |[63..66): foo <= _root_.a.b.B#foo.
        |
        |Symbols:
        |_root_.a. => package a
        |_root_.a.b. => package b
        |_root_.a.b.A# => trait A
        |_root_.a.b.A#a. => def a
        |_root_.a.b.B# => class B
        |_root_.a.b.B#foo. => def foo
        |""".stripMargin
    )
  }
}
