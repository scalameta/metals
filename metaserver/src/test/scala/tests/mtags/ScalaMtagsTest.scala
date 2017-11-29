package tests.mtags

object ScalaMtagsTest extends BaseMtagsTest {
  check(
    "vanilla.scala",
    """
      |package a.b.c
      |object D {
      |   def e = { def x = 3; x }
      |   val f = 2
      |   var g = 2
      |   class H { def x = 3 }
      |   trait I {
      |     def x: Int
      |     val y: Int
      |     var z: Int
      |   }
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
      |[127..128): x <= _root_.a.b.c.D.I#x.
      |[143..144): y <= _root_.a.b.c.D.I#y.
      |[159..160): z <= _root_.a.b.c.D.I#z.
      |[181..182): J <= _root_.a.b.c.D.J.
      |[189..190): k <= _root_.a.b.c.D.J.k.
      |[214..215): K <= _root_.a.b.c.K.
      |[224..225): l <= _root_.a.b.c.K.l.
      |
      |Symbols:
      |_root_.a.b.c.D. => object D
      |_root_.a.b.c.D.H# => class H
      |_root_.a.b.c.D.H#x. => def x
      |_root_.a.b.c.D.I# => trait I
      |_root_.a.b.c.D.I#x. => def x
      |_root_.a.b.c.D.I#y. => val y
      |_root_.a.b.c.D.I#z. => var z
      |_root_.a.b.c.D.J. => object J
      |_root_.a.b.c.D.J.k. => def k
      |_root_.a.b.c.D.e. => def e
      |_root_.a.b.c.D.f. => val f
      |_root_.a.b.c.D.g. => var g
      |_root_.a.b.c.K. => packageobject K
      |_root_.a.b.c.K.l. => def l
      """.stripMargin
  )
}
