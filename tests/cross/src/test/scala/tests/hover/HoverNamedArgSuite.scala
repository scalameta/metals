package tests.hover

import tests.pc.BaseHoverSuite

class HoverNamedArgSuite extends BaseHoverSuite {

  check(
    "named",
    """package a
      |object b {
      |  /**
      |   * Runs foo
      |   * @param named the argument
      |   */
      |  def foo(named: Int): Unit = ()
      |  <<foo(nam@@ed = 2)>>
      |}
      |""".stripMargin,
    """|**Expression type**:
       |```scala
       |Unit
       |```
       |**Symbol signature**:
       |```scala
       |def foo(named: Int): Unit
       |```
       |Runs foo
       |
       |**Parameters**
       |- `named`: the argument
       |""".stripMargin,
    compat = Map(
      "3" -> "named: Int".hover
    )
  )

  check(
    "error",
    """package a
      |object c {
      |  def foo(a: String, named: Int): Unit = ()
      |  foo(nam@@ed = 2)
      |}
      |""".stripMargin,
    ""
  )

  check(
    "error2",
    """package a
      |object d {
      |  def foo(a: Int, named: Int): Unit = ()
      |  foo("error", nam@@ed = 2)
      |}
      |""".stripMargin,
    "",
    compat = Map(
      "3" -> "named: Int".hover
    )
  )

  check(
    "nested",
    """package a
      |object e {
      |  class User(name: String, age: Int)
      |  println(<<new User(age = 42, n@@ame = "")>>)
      |}
      |""".stripMargin,
    """|User
       |def this(name: String, age: Int): User""".stripMargin.hover,
    compat = Map(
      "3" -> "name: String".hover
    )
  )

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
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hover,
    compat = Map(
      "3.0" -> "def sum[B >: A](implicit num: Numeric[B]): B".hover
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
       |```""".stripMargin,
    compat = Map(
      "3.0" -> "def +(x: Int): Int".hover
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
       |def sum[B >: Int](implicit num: Numeric[B]): B""".stripMargin.hover,
    compat = Map(
      "3.0" -> "val l: List[Int]".hover
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
       |def flatMap[B, That](f: Int => GenTraversableOnce[B])(implicit bf: CanBuildFrom[immutable.IndexedSeq[Int],B,That]): That""".stripMargin.hover,
    compat = Map(
      "3.0" -> "x: Int".hover
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
       |override def apply[A](xs: A*): List[A]""".stripMargin.hover,
    compat = Map(
      "3.0" -> "def apply[A](elems: A*): Int".hover
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
       |val l: List[Int]""".stripMargin.hover,
    compat = Map(
      "3.0" -> "".hover
    )
  )
}
