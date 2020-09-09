package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig

import tests.BaseCompletionSuite
import tests.BuildInfoVersions

class CompletionSnippetNegSuite extends BaseCompletionSuite {

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
           |""".stripMargin,
      "0." -> "apply"
    )
  )

  checkSnippet(
    "scope".tag(IgnoreScalaVersion("0.27.0-RC1")),
    """
      |object Main {
      |  printl@@
      |
      |}
      |""".stripMargin,
    """|println()
       |println
       |""".stripMargin,
    compat = Map(
      "0.26" -> "println"
    )
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
       |""".stripMargin,
    compat = Map(
      "0." -> "toString"
    )
  )

  checkSnippet(
    "type".tag(IgnoreScalaVersion(BuildInfoVersions.scala3Versions)),
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
