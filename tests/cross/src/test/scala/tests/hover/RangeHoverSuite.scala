package tests.hover

import tests.pc.BaseHoverSuite

class RangeHoverSuite extends BaseHoverSuite {

  check(
    "range-sum-method",
    """|package helpers
       |
       |class XDClass {
       |  def xd: Int = {
       |    val l = List(1,2,3)
       |    <<l.map { x =>
       |      x.to(x*x)
       |        .flatMap { y =>
       |          List(y + 2137)
       |        }
       |      .sum
       |    }.%<%sum%>%>>
       |  }
       |}
       |""".stripMargin,
    """|Int
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoverRange,
    compat = Map(
      "3" ->
        """|Int
           |def sum[B >: A](implicit num: Numeric[B]): B""".stripMargin.hoverRange
    )
  )

  check(
    "range-sum-method-generic",
    """|package helpers
       |
       |class XDClass[T] {
       |  def xd: T = {
       |    val l: List[T] = ???
       |    <<l.%<%head%>%>>
       |  }
       |}
       |""".stripMargin,
    """|T
       |override def head: T""".stripMargin.hoverRange,
    compat = Map(
      "3" ->
        """|T
           |def head: T""".stripMargin.hoverRange,
      "2.13" ->
        """|T
           |def head: T""".stripMargin.hoverRange
    )
  )

  check(
    "range-longer-expression",
    """|package helpers
       |
       |class XDClass {
       |  def xd: Int = {
       |    val l = List(1,2,3)
       |    <<%<%l.map { x =>
       |      x.to(x*x)
       |        .flatMap { y =>
       |          List(y + 2137)
       |        }
       |      .sum
       |    }.sum%>%>>
       |  }
       |}
       |""".stripMargin,
    """|Int
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoverRange,
    compat = Map(
      "3" ->
        """|Int
           |def sum[B >: A](implicit num: Numeric[B]): B""".stripMargin.hoverRange
    )
  )

  check(
    "range-longer-expression-1",
    """|package helpers
       |
       |class XDClass {
       |  def xd: Int = {
       |    val l = List(1,2,3)
       |    l.map { x =>
       |      <<%<%x.to(x*x)
       |        .flatMap { y =>
       |          List(y + 2137)
       |        }%>%>>
       |      .sum
       |    }.sum
       |  }
       |}
       |""".stripMargin,
    """|immutable.IndexedSeq[Int]
       |def flatMap[B, That](f: Int => GenTraversableOnce[B])(implicit bf: CanBuildFrom[immutable.IndexedSeq[Int],B,That]): That""".stripMargin.hoverRange,
    compat = Map(
      "2.13" ->
        """|IndexedSeq[Int]
           |override def flatMap[B](f: Int => IterableOnce[B]): IndexedSeq[B]""".stripMargin.hoverRange,
      "3" ->
        """|IndexedSeq[Int]
           |def flatMap[B](f: A => IterableOnce[B]): IndexedSeq[Int]""".stripMargin.hoverRange
    )
  )

  check(
    "range-expression-in-closure",
    """|package helpers
       |
       |class XDClass {
       |  def xd: Int = {
       |    val l = List(1,2,3)
       |    l.map { x =>
       |      x.to(x*x)
       |        .flatMap { y =>
       |          <<%<%List(y + 2137)%>%>>
       |        }
       |      .sum
       |    }.sum
       |  }
       |}
       |""".stripMargin,
    """|List[Int]
       |override def apply[A](xs: A*): List[A]""".stripMargin.hoverRange,
    compat = Map(
      "2.13" ->
        """|List[Int]
           |def apply[A](elems: A*): List[A]""".stripMargin.hoverRange,
      "3" ->
        """|List[Int]
           |def apply[A](elems: A*): List[Int]""".stripMargin.hoverRange
    )
  )

  check(
    "range-lfs-of-valdef",
    """|package helpers
       |
       |class XDClass {
       |  def xd: Int = {
       |    <<val %<%l%>% = List(1,2,3)>>
       |    l.map { x =>
       |      x.to(x*x)
       |        .flatMap { y =>
       |          List(y + 2137)
       |        }
       |      .sum
       |    }.sum
       |  }
       |}
       |""".stripMargin,
    """|List[Int]
       |val l: List[Int]""".stripMargin.hoverRange
  )

  check(
    "range-literal",
    """|package helpers
       |
       |class XDClass {
       |  def xd: Int = {
       |    val l = List(1,2,3)
       |    l.map { x =>
       |      x.to(x*x)
       |        .flatMap { y =>
       |          List(y + <<%<%2137%>%>>)
       |        }
       |      .sum
       |    }.sum
       |  }
       |}
       |""".stripMargin,
    """|```scala
       |Int
       |```""".stripMargin.hoverRange,
    compat = Map(
      "3" -> """|Int
                |def +(x: Int): Int""".stripMargin.hoverRange
    )
  )

  check(
    "range-val-lhs-in-for",
    """|package helpers
       |
       |class XDClass {
       |  def xd: List[Int] =
       |    for {
       |      a <- List(11,23,17)
       |      b <- a to a*a
       |      <<%<%x%>% = b - 3>>
       |    } yield x
       |}
       |""".stripMargin,
    """|Int
       |val x: Int""".stripMargin.hoverRange
  )

  check(
    "range-binding-lhs-in-for",
    """|package helpers
       |
       |class XDClass {
       |  def xd: List[Int] =
       |    for {
       |      a <- List(11,23,17)
       |      <<%<%b%>%>> <- a to a*a
       |      x = b - 3
       |    } yield x
       |}
       |""".stripMargin,
    """|Int
       |b: Int""".stripMargin.hoverRange
  )

  check(
    "range-wider",
    """|package helpers
       |
       |class XDClass {
       |  def xd: Int = {
       |    val l = List(1,2,3)
       |   <<l.map { x =>
       |      x.to(x*x)
       |        .flatMap { y =>
       |          List(y + 2137)
       |        }
       |      .sum
       |    }.%<%  sum%>%>>
       |  }
       |}
       |""".stripMargin,
    """|Int
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoverRange,
    compat = Map(
      "3" ->
        """|Int
           |def sum[B >: A](implicit num: Numeric[B]): B""".stripMargin.hoverRange
    )
  )

  check(
    "range-wider2",
    """|package helpers
       |
       |class XDClass {
       |  def xd: Int = {
       |    val l = List(1,2,3)
       |%<% <<l.map { x =>
       |      x.to(x*x)
       |        .flatMap { y =>
       |          List(y + 2137)
       |        }
       |      .sum
       |    }.sum>>  %>%
       |  }
       |}
       |""".stripMargin,
    """|Int
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoverRange,
    compat = Map(
      "3" ->
        """|Int
           |def sum[B >: A](implicit num: Numeric[B]): B""".stripMargin.hoverRange
    )
  )
}
