package tests.compiler

import scala.meta.languageserver.compiler.CompletionProvider
import langserver.messages.CompletionList
import langserver.types.CompletionItemKind
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
      | Opti<<>>
      |}
    """.stripMargin,
    s"""
       |{
       |  "isIncomplete" : false,
       |  "items" : [ {
       |    "label" : "Option",
       |    "kind" : ${CompletionItemKind.Module.value},
       |    "detail" : ""
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
    s"""
       |{
       |  "isIncomplete" : false,
       |  "items" : [ {
       |    "label" : "StringBuilder",
       |    "kind" : ${CompletionItemKind.Value.value},
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
    s"""
       |{
       |  "isIncomplete" : false,
       |  "items" : [ {
       |    "label" : "empty",
       |    "kind" : ${CompletionItemKind.Method.value},
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
      val name = completions.items.find(_.label == "name")
      val age = completions.items.find(_.label == "age")
      assert(name.isDefined)
      assert(age.isDefined)
      assert(name.get.kind == Some(CompletionItemKind.Field))
      assert(age.get.kind == Some(CompletionItemKind.Field))
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
    s"""
       |{
       |  "isIncomplete" : false,
       |  "items" : [ {
       |    "label" : "TestTrait",
       |    "kind" : ${CompletionItemKind.Interface.value},
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
    s"""
       |{
       |  "isIncomplete" : false,
       |  "items" : [ {
       |    "label" : "testObject",
       |    "kind" : ${CompletionItemKind.Module.value},
       |    "detail" : ""
       |  } ]
       |}
    """.stripMargin
  )

  check(
    "package",
    """
      | import scala.collect<<>>
    """.stripMargin,
    s"""
       |{
       |  "isIncomplete" : false,
       |  "items" : [ {
       |    "label" : "collection",
       |    "kind" : ${CompletionItemKind.Module.value},
       |    "detail" : ""
       |  } ]
       |}
    """.stripMargin
  )

}
