package scala.meta.languageserver.ctags

class JavaCtagsTest extends BaseCtagsTest {
  check(
    "interface.java",
    """package a.b;
      |interface A {
      |  public String a();
      |}
      |""".stripMargin,
    """
      |Language:
      |Java
      |
      |Names:
      |[8..11): a.b => _root_.a.
      |[8..11): a.b => _root_.a.b.
      |[23..24): A <= _root_.a.b.A#
      |[43..44): a <= _root_.a.b.A#a.
      |
      |Symbols:
      |_root_.a. => package a
      |_root_.a.b. => package b
      |_root_.a.b.A# => trait A
      |_root_.a.b.A#a. => def a
      |""".stripMargin
  )
  check(
    "class.java",
    """
      |
      |class B {
      |  public static void c() { }
      |  public int d() { }
      |  public class E {}
      |  public static class F {}
      |}
    """.stripMargin,
    """
      |Language:
      |Java
      |
      |Names:
      |[8..9): B <= _root_.B#
      |[33..34): c <= _root_.B.c.
      |[54..55): d <= _root_.B#d.
      |[77..78): E <= _root_.B#E#
      |[104..105): F <= _root_.B.F#
      |
      |Symbols:
      |_root_.B# => class B
      |_root_.B#E# => class E
      |_root_.B#d. => def d
      |_root_.B.F# => class F
      |_root_.B.c. => def c
    """.stripMargin
  )
  check(
    "enum.java",
    """
      |enum G {
      |  H,
      |  I
      |}
    """.stripMargin,
    """
      |Language:
      |Java
      |
      |Names:
      |[6..7): G <= _root_.G.
      |[12..13): H <= _root_.G.H.
      |[17..18): I <= _root_.G.I.
      |
      |Symbols:
      |_root_.G. => object G
      |_root_.G.H. => val H
      |_root_.G.I. => val I
      |""".stripMargin
  )

  check(
    "field.java",
    """
      |public class J {
      |    public static final int FIELD = 1;
      |}
    """.stripMargin,
    """
      |Language:
      |Java
      |
      |Names:
      |[14..15): J <= _root_.J#
      |[46..51): FIELD <= _root_.J.FIELD.
      |
      |Symbols:
      |_root_.J# => class J
      |_root_.J.FIELD. => val FIELD
    """.stripMargin
  )
}
