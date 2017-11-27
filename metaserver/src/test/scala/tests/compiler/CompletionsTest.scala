package tests.compiler

import scala.meta.languageserver.compiler.CompletionProvider
import langserver.messages.CompletionList
import play.api.libs.json.Json

object CompletionsTest extends CompilerSuite {

  def check(
      filename: String,
      code: String,
      fn: CompletionList => Unit
  ): Unit = {
    targeted(
      filename,
      code, { pos =>
        val obtained = CompletionProvider.completions(compiler, pos)
        fn(obtained)
      }
    )
  }

  def check(
      filename: String,
      code: String,
      expected: String
  ): Unit = {
    check(
      filename,
      code, { completions =>
        val obtained = Json.prettyPrint(Json.toJson(completions))
        assertNoDiff(obtained, expected)
      }
    )
  }

  check(
    "companion object",
    """
      |object a {
      | Lis<<>>
      |}
    """.stripMargin,
    """
      |{
      |  "isIncomplete" : false,
      |  "items" : [ {
      |    "label" : "List",
      |    "kind" : 2,
      |    "detail" : ": collection.immutable.List.type"
      |  } ]
      |}
    """.stripMargin
  )

  check(
    "empty",
    """
      |object a <<>>
    """.stripMargin,
    """
      |{
      |  "isIncomplete" : false,
      |  "items" : [ ]
      |}
    """.stripMargin
  )

  check(
    "ctor",
    """
      |object a {
      | new StringBui<<>>
      |}
    """.stripMargin,
    // PC seems to return the companion object, which is incorrect
    // since we're in ype position.
    """
      |{
      |  "isIncomplete" : false,
      |  "items" : [ {
      |    "label" : "StringBuilder",
      |    "kind" : 2,
      |    "detail" : ": collection.mutable.StringBuilder.type"
      |  } ]
      |}
    """.stripMargin
  )

  check(
    "method",
    """
      |object a {
      | List.em<<>>
      |}
    """.stripMargin,
    """
      |{
      |  "isIncomplete" : false,
      |  "items" : [ {
      |    "label" : "empty",
      |    "kind" : 6,
      |    "detail" : "[A]: List[A]"
      |  } ]
      |}
    """.stripMargin
  )

  check(
    "case",
    """
      |case class User(name: String, age: Int)
      |object a {
      |  User("", 1).<<>>
      |}
    """.stripMargin, { completions =>
      val completionLength = completions.items.length
      assert(completionLength > 2)
      val completionLabels = completions.items.map(_.label)
      assert(completionLabels.contains("name"))
      assert(completionLabels.contains("age"))
    }
  )

  check(
    "trait",
    """
      |trait TestTrait
      |object a {
      |  val x: TestTr<<>>
      |}
    """.stripMargin,
    """
      |{
      |  "isIncomplete" : false,
      |  "items" : [ {
      |    "label" : "TestTrait",
      |    "kind" : 11,
      |    "detail" : " extends "
      |  } ]
      |}
    """.stripMargin
  )

  check(
    "object",
    """
      |object testObject
      |object a {
      |  testObj<<>>
      |}
    """.stripMargin,
    """
      |{
      |  "isIncomplete" : false,
      |  "items" : [ {
      |    "label" : "testObject",
      |    "kind" : 2,
      |    "detail" : ""
      |  } ]
      |}
    """.stripMargin
  )

}
