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
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoveRanger,
    compat = Map(
      "3.0" -> "def sum[B >: A](implicit num: Numeric[B]): B".hoveRanger
    )
  )

  //TODO this IMHO actually is a bug, the resut should also conain the actual type -> Int
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
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hoveRanger,
    compat = Map(
      "3.0" -> "val l: List[Int]".hoveRanger
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
       |def flatMap[B, That](f: Int => GenTraversableOnce[B])(implicit bf: CanBuildFrom[immutable.IndexedSeq[Int],B,That]): That""".stripMargin.hoveRanger,
    compat = Map(
      "2.13" ->
        """|IndexedSeq[Int]
           |override def flatMap[B](f: Int => IterableOnce[B]): IndexedSeq[B]""".stripMargin.hoveRanger,
      "3.0" -> "x: Int".hoveRanger
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
       |override def apply[A](xs: A*): List[A]""".stripMargin.hoveRanger,
    compat = Map(
      "2.13" ->
        """|List[Int]
           |def apply[A](elems: A*): List[A]""".stripMargin.hoveRanger,
      "3.0" -> "def apply[A](elems: A*): Int".hoveRanger
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
       |val l: List[Int]""".stripMargin.hoveRanger,
    compat = Map(
      "3.0" -> "val l: List[Int]".hoveRanger
    )
  )
}
