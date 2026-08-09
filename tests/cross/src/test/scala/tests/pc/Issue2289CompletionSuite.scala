package tests.pc

import coursierapi.Dependency
import tests.BaseCompletionSuite

/**
 * Regression test for https://github.com/scalameta/metals/issues/2289
 *
 * When the type of a `val` is inferred through an implicit instance materialized
 * by a (blackbox) macro - here caliban's `Schema` derivation - the presentation
 * compiler used to skip that macro expansion, the implicit search failed and the
 * `val` ended up with an error type, so no completions/hover were offered. With an
 * explicit type annotation it worked. See `MetalsGlobal#pluginsMacroExpand`.
 */
class Issue2289CompletionSuite extends BaseCompletionSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    Seq(
      Dependency.of("com.github.ghostdogpr", s"caliban_$binaryVersion", "3.1.2")
    )
  }

  // The reproduction only type-checks under Scala 2.13 (caliban 3.x's auto
  // derivation does not resolve `Schema` on 2.12, even with the batch compiler),
  // so this is the version where the PC-specific regression is observable.
  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScalaVersion(!_.startsWith("2.13")))

  override def beforeAll(): Unit = ()

  private val preamble =
    """|import caliban._
       |import caliban.schema.Schema.auto._
       |
       |object Main {
       |  case class Character(name: String, age: Option[Int])
       |  def getCharacters: List[Character] = List()
       |  case class Queries(characters: List[Character])
       |  val queries = Queries(getCharacters)
       |""".stripMargin

  checkItems(
    "inferred-type-from-implicit-macro",
    preamble +
      "  val api = graphQL(RootResolver(queries))\n  api.re@@\n}\n",
    items => {
      val labels = items.map(_.getLabel())
      // `render` / `renderCompact` are members of the inferred `GraphQL[_]` type
      labels.exists(_.startsWith("render")) &&
      labels.exists(_.startsWith("renderCompact"))
    }
  )
}
