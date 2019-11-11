package tests.pc

import tests.BaseCompletionSuite
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.internal.pc.PresentationCompilerConfigImpl

object CompletionSnippetNegSuite extends BaseCompletionSuite {

  override def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl(
      isCompletionSnippetsEnabled = false
    )

  checkSnippet(
    "member",
    """
      |object Main {
      |  List.appl@@
      |}
      |""".stripMargin,
    """|apply
       |unapplySeq
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        // the second apply is from scala/collection/BuildFrom#apply(), introduced in 2.13
        """|apply
           |unapplySeq
           |apply
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
       |println
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
    // It's expected to have two separate results, one for `object IndexedSeq` and one for `type IndexedSeq[T]`.
    """|IndexedSeq
       |IndexedSeq
       |""".stripMargin
  )

}
