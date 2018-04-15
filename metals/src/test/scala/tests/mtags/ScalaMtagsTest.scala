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
      |Scala
      |
      |Names:
      |[22..23): D <= a.b.c.D.
      |[33..34): e <= a.b.c.D.e().
      |[61..62): f <= a.b.c.D.f().
      |[74..75): g <= a.b.c.D.g().
      |[89..90): H <= a.b.c.D.H#
      |[97..98): x <= a.b.c.D.H#x().
      |[114..115): I <= a.b.c.D.I#
      |[127..128): x <= a.b.c.D.I#x().
      |[143..144): y <= a.b.c.D.I#y().
      |[159..160): z <= a.b.c.D.I#z().
      |[181..182): J <= a.b.c.D.J.
      |[189..190): k <= a.b.c.D.J.k().
      |
      |Symbols:
      |a.b.c.D. => object D
      |a.b.c.D.H# => class H
      |a.b.c.D.H#x(). => method x
      |a.b.c.D.I# => trait I
      |a.b.c.D.I#x(). => method x
      |a.b.c.D.I#y(). => val method y
      |a.b.c.D.I#z(). => var method z
      |a.b.c.D.J. => object J
      |a.b.c.D.J.k(). => method k
      |a.b.c.D.e(). => method e
      |a.b.c.D.f(). => val method f
      |a.b.c.D.g(). => var method g
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
      |Scala
      |
      |Names:
      |[16..17): K <= K.
      |[16..17): K <= K.package.
      |[26..27): l <= K.package.l().
      |
      |Symbols:
      |K. => packageobject K
      |K.package. => object package
      |K.package.l(). => method l
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
      |Scala
      |
      |Names:
      |[8..12): pats <= pats.
      |[21..22): o <= pats.o().
      |[24..25): p <= pats.p().
      |[36..37): q <= pats.q().
      |[39..40): r <= pats.r().
      |[52..53): s <= pats.s().
      |[55..56): t <= pats.t().
      |[67..68): v <= pats.v().
      |[70..71): w <= pats.w().
      |
      |Symbols:
      |pats. => object pats
      |pats.o(). => val method o
      |pats.p(). => val method p
      |pats.q(). => val method q
      |pats.r(). => val method r
      |pats.s(). => var method s
      |pats.t(). => var method t
      |pats.v(). => var method v
      |pats.w(). => var method w
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
      |Scala
      |
      |Names:
      |[7..10): Tpe <= Tpe#
      |[20..21): M <= Tpe#M#
      |[29..30): N <= Tpe#N#
      |
      |Symbols:
      |Tpe# => trait Tpe
      |Tpe#M# => type M
      |Tpe#N# => type N
    """.stripMargin
  )

  check(
    "class-field.scala",
    "case class A(a: Int, b: String)",
    """
      |Language:
      |Scala
      |
      |Names:
      |[11..12): A <= A#
      |[13..14): a <= A#(a)
      |[21..22): b <= A#(b)
      |
      |Symbols:
      |A# => class A
      |A#(a) => param a
      |A#(b) => param b
      |""".stripMargin
  )

  check(
    "methods.scala",
    """
      |abstract class Methods {
      |  def m1(a: Int, b: String): Int
      |  def m2(a: A[Int]): Unit
      |  def m3(a: A.type)
      |  def m4(a: b.A)
      |  def m5(a: => A)
      |  def m6(a: A*)
      |  def m7(a: A with B)
      |  def m8(a: {def b:B})
      |  def m9(a: () => A)
      |  def m10(a: (A, B))
      |  def m11()
      |  def m12(a: b.A)
      |  def m12(a: c.A)
      |}
    """.stripMargin,
    """
      |Language:
      |Scala
      |
      |Names:
      |[16..23): Methods <= Methods#
      |[32..34): m1 <= Methods#m1(Int,String).
      |[65..67): m2 <= Methods#m2(A).
      |[91..93): m3 <= Methods#m3(type).
      |[111..113): m4 <= Methods#m4(A).
      |[128..130): m5 <= Methods#m5(=>A).
      |[146..148): m6 <= Methods#m6(A*).
      |[162..164): m7 <= Methods#m7({}).
      |[184..186): m8 <= Methods#m8({}).
      |[207..209): m9 <= Methods#m9().
      |[228..231): m10 <= Methods#m10(A,B).
      |[249..252): m11 <= Methods#m11().
      |[261..264): m12 <= Methods#m12(A).
      |[279..282): m12 <= Methods#m12(A+1).
      |
      |Symbols:
      |Methods# => class Methods
      |Methods#m1(Int,String). => method m1
      |Methods#m10(A,B). => method m10
      |Methods#m11(). => method m11
      |Methods#m12(A). => method m12
      |Methods#m12(A+1). => method m12
      |Methods#m2(A). => method m2
      |Methods#m3(type). => method m3
      |Methods#m4(A). => method m4
      |Methods#m5(=>A). => method m5
      |Methods#m6(A*). => method m6
      |Methods#m7({}). => method m7
      |Methods#m8({}). => method m8
      |Methods#m9(). => method m9
      |""".stripMargin
  )
}
