package tests.pc

import tests.BaseCompletionSuite

object CompletionSnippetSuite extends BaseCompletionSuite {
  checkSnippet(
    "member",
    """
      |object Main {
      |  List.appl@@
      |}
      |""".stripMargin,
    """|apply($0)
       |""".stripMargin
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
    """|ju.ArrayDeque[$0]
       |""".stripMargin
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
       |""".stripMargin
  )

  checkSnippet(
    "type6",
    s"""|object Main {
        |  def foo: scala.Iterable@@
        |}
        |""".stripMargin,
    """|Iterable
       |Iterable[$0]
       |""".stripMargin
  )

  checkSnippet(
    "type7",
    s"""|object Main {
        |  def foo(param: List[scala.Iterable@@])
        |}
        |""".stripMargin,
    """|Iterable
       |Iterable[$0]
       |""".stripMargin
  )

}
