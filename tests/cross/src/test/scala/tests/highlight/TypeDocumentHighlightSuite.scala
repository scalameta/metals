package tests.highlight

import tests.BaseDocumentHighlightSuite

class TypeDocumentHighlightSuite extends BaseDocumentHighlightSuite {

  check(
    "type1",
    """
      |object Test {
      |  type <<NotI@@nt>> = Int
      |  val set = Set.empty[<<NotInt>>]
      |}""".stripMargin,
  )

  check(
    "type2",
    """
      |object Test {
      |  type <<NotInt>> = Int
      |  val set = Set.empty[<<Not@@Int>>]
      |}""".stripMargin,
  )
  check(
    "type3",
    """
      |object Test {
      |  type NotInt = <<In@@t>>
      |  val set = Set.empty[<<Int>>]
      |}""".stripMargin,
  )

  check(
    "type4",
    """
      |object Test {
      |  type NotInt = <<Int>>
      |  val set = Set.empty[<<I@@nt>>]
      |}""".stripMargin,
  )

  check(
    "type-in-def2",
    """
      |object Test {
      |  var bspSession: Option[<<Stri@@ng>>] =
      |    Option.empty[<<String>>]
      |}""".stripMargin,
  )
  check(
    "type-in-def2",
    """
      |object Test {
      |  var bspSession: Option[<<String>>] =
      |    Option.empty[<<Stri@@ng>>]
      |}""".stripMargin,
  )

  check(
    "type-in-def3",
    """
      |object Test {
      |  var bspSession: <<Op@@tion>>[String] =
      |    <<Option>>.empty[String]
      |}""".stripMargin,
  )
  check(
    "type-in-def4",
    """
      |object Test {
      |  var bspSession: <<Option>>[String] =
      |    <<Opt@@ion>>.empty[String]
      |}""".stripMargin,
  )

  check(
    "type-bounds1",
    """
      |object Test {
      |  type A = List[_ <: <<It@@erable>>[Int]]
      |  val a : <<Iterable>>[Int] = ???
      |}""".stripMargin,
  )

  check(
    "type-bounds2",
    """
      |object Test {
      |  type A = List[_ <: <<Iterable>>[Int]]
      |  val a : <<Ite@@rable>>[Int] = ???
      |}""".stripMargin,
  )

  check(
    "type-bounds3",
    """
      |object Test {
      |  type A = List[_ <: scala.<<Enumerati@@on>>]
      |  val a : scala.<<Enumeration>> = ???
      |}""".stripMargin,
  )

  check(
    "type-bounds4",
    """
      |object Test {
      |  type A = List[_ <: scala.<<Enumeration>>]
      |  val a : scala.<<Enumer@@ation>> = ???
      |}""".stripMargin,
  )

  check(
    "type-bounds5",
    """
      |object Test {
      |  type A = List[_ <: Iterable[<<In@@t>>]]
      |  val a : Iterable[<<Int>>] = ???
      |}""".stripMargin,
  )

  check(
    "type-bounds6",
    """
      |object Test {
      |  type A = List[_ <: Iterable[<<Int>>]]
      |  val a : Iterable[<<In@@t>>] = ???
      |}""".stripMargin,
  )

  check(
    "annot1".tag(IgnoreScalaVersion("3.1.0", "3.1.1", "3.1.2")),
    """
      |object Test {
      |  @<<depr@@ecated>>(since = "1.23")
      |  val hello1 = 123
      |
      |  @<<deprecated>>(since = "1.23")
      |  val hello2 = 123
      |}""".stripMargin,
  )

  check(
    "annot2".tag(IgnoreScalaVersion("3.1.0", "3.1.1", "3.1.2")),
    """
      |object Test {
      |  @deprecated(<<since>> = "1.23")
      |  val hello1 = 123
      |
      |  @deprecated(<<si@@nce>> = "1.23")
      |  val hello2 = 123
      |}""".stripMargin,
  )

  check(
    "projection1",
    """|
       |class A {
       |    type <<B@@B>> = Int
       |  }
       |  object Test {
       |    val b1: A#<<BB>> = 12
       |}""".stripMargin,
  )

  check(
    "projection2",
    """|
       |class A {
       |    type <<BB>> = Int
       |  }
       |  object Test {
       |    val b1: A#<<B@@B>> = 12
       |}""".stripMargin,
  )

}
