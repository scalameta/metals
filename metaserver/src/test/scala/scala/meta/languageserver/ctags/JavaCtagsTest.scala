package scala.meta.languageserver.ctags

import scala.meta.testkit.DiffAssertions
import org.scalatest.FunSuite

class JavaCtagsTest extends FunSuite with DiffAssertions {
  test("index java source") {
    val obtained = Ctags.index(
      "a.java",
      """package a.b;
        |interface A { String a(); }
        |class B {
        |  static void c() { }
        |  int d() { }
        |  class E {}
        |  static class F {}
        |}
        |""".stripMargin
    )
    println(obtained.syntax)
    assertNoDiff(
      obtained.syntax,
      """
        |Language:
        |Java
        |
        |Names:
        |[8..11): a.b => _root_.a.
        |[8..11): a.b => _root_.a.b.
        |[23..24): A <= _root_.a.b.A#
        |[34..35): a <= _root_.a.b.A#a.
        |[47..48): B <= _root_.a.b.B#
        |[65..66): c <= _root_.a.b.B.c.
        |[79..80): d <= _root_.a.b.B#d.
        |[95..96): E <= _root_.a.b.B#E#
        |[115..116): F <= _root_.a.b.B.F#
        |
        |Symbols:
        |_root_.a. => package a
        |_root_.a.b. => package b
        |_root_.a.b.A# => trait A
        |_root_.a.b.A#a. => def a
        |_root_.a.b.B# => class B
        |_root_.a.b.B#E# => class E
        |_root_.a.b.B#d. => def d
        |_root_.a.b.B.F# => class F
        |_root_.a.b.B.c. => def c
        |""".stripMargin
    )
  }
}
