package tests.highlight

import tests.BaseDocumentHighlightSuite

class Scala3DocumentHighlightSuite extends BaseDocumentHighlightSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)

  check(
    "transparent1",
    """|trait Foo
       |class Bar extends Foo
       |
       |transparent inline def <<foo>>(i: Int): Foo = new Bar
       |val iii = 123
       |val bar = <<f@@oo>>(iii)
       |""".stripMargin,
  )

  check(
    "transparent2",
    """|trait Foo
       |class Bar extends Foo
       |
       |transparent inline def <<f@@oo>>(i: Int): Foo = new Bar
       |val iii = 123
       |val bar = <<foo>>(iii)
       |""".stripMargin,
  )

  check(
    "transparent3",
    """|trait Foo
       |class Bar extends Foo
       |
       |transparent inline def foo(i: Int): Foo = new Bar
       |val <<ii@@i>> = 123
       |val bar = foo(<<iii>>)
       |""".stripMargin,
  )

  check(
    "transparent4",
    """|trait Foo
       |class Bar extends Foo
       |
       |transparent inline def foo(i: Int): Foo = new Bar
       |val <<iii>> = 123
       |val bar = foo(<<i@@ii>>)
       |""".stripMargin,
  )

  check(
    "extension-params",
    """|extension (<<sb@@d>>: String)
       |  def double = <<sbd>> + <<sbd>>
       |  def double2 = <<sbd>> + <<sbd>>
       |end extension
       |""".stripMargin,
  )

  check(
    "extension-params-ref",
    """|extension (<<sbd>>: String)
       |  def double = <<sb@@d>> + <<sbd>>
       |  def double2 = <<sbd>> + <<sbd>>
       |end extension
       |""".stripMargin,
  )

  check(
    "extension-type-param",
    """|extension [T](<<x@@s>>: List[T])
       |  def double = <<xs>> ++ <<xs>>
       |  def double2 = <<xs>> ++ <<xs>>
       |end extension
       |""".stripMargin,
  )

  check(
    "extension-type-param-ref",
    """|extension [T](<<xs>>: List[T])
       |  def double = <<xs>> ++ <<xs>>
       |  def double2 = <<xs>> ++ <<x@@s>>
       |end extension
       |""".stripMargin,
  )

  check(
    "extension-complex",
    """|object Extensions:
       |
       |  extension [A, B](<<eit@@hers>>: Seq[Either[A, B]])
       |    def sequence = <<eithers>>.partitionMap(identity) match
       |      case (Nil, rights)       => Right(rights)
       |      case (firstLeft :: _, _) => Left(firstLeft)
       |    def sequence2 = <<eithers>>.partitionMap(identity) match
       |      case (Nil, rights)       => Right(rights)
       |      case (firstLeft :: _, _) => Left(firstLeft)
       |
       |  extension (map: Map[String, String])
       |    def getOrLeft(key: String): Either[String, String] =
       |      map.get(key) match
       |        case None        => Left(s"Missing ${key} in }")
       |        case Some(value) => Right(value)
       |""".stripMargin,
  )

}
