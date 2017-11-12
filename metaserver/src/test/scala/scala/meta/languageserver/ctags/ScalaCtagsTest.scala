package scala.meta.languageserver.ctags

class ScalaCtagsTest extends BaseCtagsTest {
  check(
    "vanilla.scala",
    """
      |package a.b.c
      |object D {
      |   def e = { def x = 3; x }
      |   val f = 2
      |   var g = 2
      |   class H { def x = 3 }
      |   trait I { def x = 3 }
      |   object J { def k = 2 }
      |}
      |package object K {
      |  def l = 2
      |}
      """.stripMargin,
    """
      |Language:
      |Scala212
      |
      |Names:
      |[22..23): D <= _root_.a.b.c.D.
      |[33..34): e <= _root_.a.b.c.D.e.
      |[61..62): f <= _root_.a.b.c.D.f.
      |[74..75): g <= _root_.a.b.c.D.g.
      |[89..90): H <= _root_.a.b.c.D.H#
      |[97..98): x <= _root_.a.b.c.D.H#x.
      |[114..115): I <= _root_.a.b.c.D.I#
      |[122..123): x <= _root_.a.b.c.D.I#x.
      |[140..141): J <= _root_.a.b.c.D.J.
      |[148..149): k <= _root_.a.b.c.D.J.k.
      |[173..174): K <= _root_.a.b.c.K.
      |[183..184): l <= _root_.a.b.c.K.l.
      |
      |Symbols:
      |_root_.a.b.c.D. => object D
      |_root_.a.b.c.D.H# => class H
      |_root_.a.b.c.D.H#x. => def x
      |_root_.a.b.c.D.I# => trait I
      |_root_.a.b.c.D.I#x. => def x
      |_root_.a.b.c.D.J. => object J
      |_root_.a.b.c.D.J.k. => def k
      |_root_.a.b.c.D.e. => def e
      |_root_.a.b.c.D.f. => def f
      |_root_.a.b.c.D.g. => def g
      |_root_.a.b.c.K. => packageobject K
      |_root_.a.b.c.K.l. => def l
      """.stripMargin
  )
}
