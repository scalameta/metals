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
      """.stripMargin
  )

  check(
    "pkgobject.scala",
    """
      |package object K {
      |  def l = 2
      |}
    """.stripMargin,
    """
      |Language:
      |Scala212
      |
      |Names:
      |[16..17): K <= _root_.K.
      |[16..17): K <= _root_.K.package.
      |[26..27): l <= _root_.K.package.l.
      |
      |Symbols:
      |_root_.K. => packageobject K
      |_root_.K.package. => object package
      |_root_.K.package.l. => def l
    """.stripMargin
  )

  check(
    "pats.scala",
    """
      |object pats {
      |  val o, p = 2
      |  val q, r: Int
      |  var s, t = 2
      |  var v, w: Int
      |}
    """.stripMargin,
    """
      |Language:
      |Scala212
      |
      |Names:
      |[8..12): pats <= _root_.pats.
      |[21..22): o <= _root_.pats.o.
      |[24..25): p <= _root_.pats.p.
      |[36..37): q <= _root_.pats.q.
      |[39..40): r <= _root_.pats.r.
      |[52..53): s <= _root_.pats.s.
      |[55..56): t <= _root_.pats.t.
      |[67..68): v <= _root_.pats.v.
      |[70..71): w <= _root_.pats.w.
      |
      |Symbols:
      |_root_.pats. => object pats
      |_root_.pats.o. => val o
      |_root_.pats.p. => val p
      |_root_.pats.q. => val q
      |_root_.pats.r. => val r
      |_root_.pats.s. => var s
      |_root_.pats.t. => var t
      |_root_.pats.v. => var v
      |_root_.pats.w. => var w
    """.stripMargin
  )

  check(
    "type.scala",
    """
      |trait Tpe {
      |  type M
      |  type N = F
      |}
    """.stripMargin,
    """
      |Language:
      |Scala212
      |
      |Names:
      |[7..10): Tpe <= _root_.Tpe#
      |[20..21): M <= _root_.Tpe#M#
      |[29..30): N <= _root_.Tpe#N#
      |
      |Symbols:
      |_root_.Tpe# => trait Tpe
      |_root_.Tpe#M# => type M
      |_root_.Tpe#N# => type N
    """.stripMargin
  )

  check(
    "class-field.scala",
    "case class A(a: Int, b: String)",
    """
      |Language:
      |Scala212
      |
      |Names:
      |[11..12): A <= _root_.A#
      |[13..14): a <= _root_.A#(a)
      |[21..22): b <= _root_.A#(b)
      |
      |Symbols:
      |_root_.A# => class A
      |_root_.A#(a) => val a
      |_root_.A#(b) => val b
      |""".stripMargin
  )
}
