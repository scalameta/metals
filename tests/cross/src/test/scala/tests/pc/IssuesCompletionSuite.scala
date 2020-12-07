package tests.pc

import scala.collection.Seq

import coursierapi.Dependency
import tests.BaseCompletionSuite

class IssuesCompletionSuite extends BaseCompletionSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {

    val scalaBinaryVersion = createBinaryVersion(scalaVersion)
    if (isScala3Version(scalaVersion)) {
      Seq.empty
    } else {
      Seq(
        Dependency
          .of("com.github.ghostdogpr", s"caliban_$scalaBinaryVersion", "0.9.3")
      )
    }
  }

  check(
    "issue-2289",
    """
      |import caliban.GraphQL.graphQL
      |import caliban.RootResolver
      |import caliban.GraphQL
      |
      |object Main {
      |  case class Character(name: String, age: Option[Int])
      |
      |  def getCharacters: List[Character] = List()
      |
      |  case class Queries(characters: List[Character])
      |
      |  val queries = Queries(getCharacters)
      |
      |  def main(args: Array[String]): Unit = {
      |    val api = graphQL(RootResolver(queries))
      |    api.ren@@
      |  }
      |}
      |""".stripMargin,
    """|render
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|from(r: ::[String,::[Int,HNil]]): Person
           |""".stripMargin
    )
  )

}
