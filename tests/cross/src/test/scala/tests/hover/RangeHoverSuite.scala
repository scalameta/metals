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
    """|B
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoverRange,
    compat = Map(
      "3" -> "def sum[B >: A](implicit num: Numeric[B]): B".hoverRange
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
    """|B
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoverRange,
    compat = Map(
      "3" -> "val l: List[Int]".hoverRange
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
      "3" -> "x: Int".hoverRange
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
      "3" -> "def apply[A](elems: A*): Int".hoverRange
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
       |val l: List[Int]""".stripMargin.hoverRange,
    compat = Map(
      "3" -> "val l: List[Int]".hoverRange
    )
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
      "3" -> "def +(x: Int): Int".hoverRange
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
       |val x: Int""".stripMargin.hoverRange,
    compat = Map(
      "3" -> "val x: Int".hoverRange
    )
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
       |b: Int""".stripMargin.hoverRange,
    compat = Map(
      "3" -> "b: Int".hoverRange
    )
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
    """|B
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoverRange,
    compat = Map(
      "3" -> "def sum[B >: A](implicit num: Numeric[B]): B".hoverRange
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
    """|B
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoverRange,
    compat = Map(
      "3" -> "def sum[B >: A](implicit num: Numeric[B]): B".hoverRange
    )
  )
}
