package tests.pc

import tests.BaseCompletionSuite

class CompletionSnippetSuite extends BaseCompletionSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala3)

  checkSnippet(
    "member",
    """
      |object Main {
      |  List.appl@@
      |}
      |""".stripMargin,
    """|apply($0)
       |unapplySeq($0)
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        // the second apply is from scala/collection/BuildFrom#apply(), introduced in 2.13
        """|apply($0)
           |unapplySeq($0)
           |apply($0)
           |""".stripMargin
    )
  )

  checkSnippet(
    "scope",
    """
      |object Main {
      |  printl@@
      |
      |}
      |""".stripMargin,
    """|println()
       |println($0)
       |""".stripMargin
  )

  checkSnippet(
    "nullary",
    """
      |object Main {
      |  List(1).hea@@
      |}
      |""".stripMargin,
    """|head
       |headOption
       |""".stripMargin
  )

  checkSnippet(
    "java-nullary",
    """
      |class Foo {
      |  override def toString = "Foo"
      |}
      |object Main {
      |  new Foo().toStrin@@
      |
      |}
      |""".stripMargin,
    // even if `Foo.toString` is nullary, it overrides `Object.toString()`
    // which is a Java non-nullary method with an empty parameter list.
    """|toString()
       |""".stripMargin
  )

  checkSnippet(
    "type-empty",
    """
      |object Main {
      |  type MyType = List[Int]
      |  def list : MT@@
      |}
      |""".stripMargin,
    """|MyType
       |""".stripMargin
  )

  checkSnippet(
    "type-new-empty",
    """
      |object Main {
      |  class Gen[T]
      |  type MyType = Gen[Int]
      |  new MT@@
      |}
      |""".stripMargin,
    """|MyType
       |""".stripMargin
  )

  checkSnippet(
    "type",
    s"""|object Main {
        |  val x: scala.IndexedSe@@
        |}
        |""".stripMargin,
    // It's expected to have two separate results, one for `object IndexedSeq` (which should not
    // expand snipppet) and one for `type IndexedSeq[T]`.
    """|IndexedSeq
       |IndexedSeq[$0]
       |""".stripMargin
  )

  checkSnippet(
    "type2",
    s"""|object Main {
        |  new scala.IndexedSeq@@
        |}
        |""".stripMargin,
    """|IndexedSeq
       |IndexedSeq[$0] {}
       |""".stripMargin
  )

  checkSnippet(
    "type3",
    s"""|object Main {
        |  def foo(param: ArrayDeque@@)
        |}
        |""".stripMargin,
    """|ArrayDeque[$0]
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|ArrayDeque[$0]
           |ArrayDeque
           |ArrayDequeOps
           |""".stripMargin
    )
  )

  checkSnippet(
    "type4",
    s"""|object Main {
        |  new SimpleFileVisitor@@
        |}
        |""".stripMargin,
    """|SimpleFileVisitor[$0]
       |""".stripMargin
  )

  checkSnippet(
    "type5",
    s"""|object Main {
        |  new scala.Iterabl@@
        |}
        |""".stripMargin,
    """|Iterable
       |Iterable[$0] {}
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|Iterable
           |Iterable[$0] {}
           |IterableOnce[$0] {}
           |""".stripMargin
    )
  )

  checkSnippet(
    "type6",
    s"""|object Main {
        |  def foo: scala.Iterable@@
        |}
        |""".stripMargin,
    """|Iterable
       |Iterable[$0]
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|Iterable
           |Iterable[$0]
           |IterableOnce[$0]
           |""".stripMargin
    )
  )

  checkSnippet(
    "type7",
    s"""|object Main {
        |  def foo(param: List[scala.Iterable@@])
        |}
        |""".stripMargin,
    """|Iterable
       |Iterable[$0]
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|Iterable
           |Iterable[$0]
           |IterableOnce[$0]
           |""".stripMargin
    )
  )

  checkEditLine(
    "trailing-paren",
    s"""|object Main {
        |  def trailing(a: Int) = ()
        |  ___
        |}
        |""".stripMargin,
    "trailing@@()",
    "trailing()"
  )

  checkEditLine(
    "trailing-brace",
    s"""|object Main {
        |  def trailing(a: Int) = ()
        |  ___
        |}
        |""".stripMargin,
    "trailing@@ { }",
    "trailing { }"
  )

  checkEditLine(
    "trailing-brace1",
    s"""|object Main {
        |  def trailing(a: Int) = ()
        |  ___
        |}
        |""".stripMargin,
    "trailing@@{ }",
    "trailing{ }"
  )

  checkEditLine(
    "trailing-eta",
    s"""|object Main {
        |  def trailing(a: Int) = ()
        |  ___
        |}
        |""".stripMargin,
    "trailing@@ _",
    "trailing _"
  )

  checkEditLine(
    "implicit",
    s"""|object Main {
        |  ___
        |}
        |""".stripMargin,
    "List(1).flatte@@",
    "List(1).flatten"
  )

  checkEditLine(
    "bug1",
    s"""|object Main {
        |  ___
        |}
        |""".stripMargin,
    "scala.util.Try@@(1)",
    "scala.util.Try(1)"
  )

  checkEditLine(
    "symbol",
    s"""|object Main {
        |  val out = new StringBuilder()
        |  ___
        |}
        |""".stripMargin,
    "out.+@@=('a')",
    "out.++==('a')",
    filter = _.contains("++=(s: String)")
  )

}
