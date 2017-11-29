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

  def check(
      filename: String,
      code: String,
      label: String,
      kind: CompletionItemKind,
      detail: String
  ): Unit = {
    check(
      filename,
      code,
      s"""
         |{
         |  "isIncomplete" : false,
         |  "items" : [ {
         |    "label" : "$label",
         |    "kind" : ${kind.value},
         |    "detail" : "$detail",
         |    "sortText" : "00000"
         |  } ]
         |}
    """.stripMargin
    )
  }

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
    "companion object",
    """
      |object a {
      | Opti<<>>
      |}
    """.stripMargin,
    label = "Option",
    kind = CompletionItemKind.Module,
    detail = ""
  )

  check(
    "ctor",
    """
      |object a {
      | new StringBui<<>>
      |}
    """.stripMargin,
    label = "StringBuilder",
    kind = CompletionItemKind.Value,
    detail = " = StringBuilder"
  )

  check(
    "method",
    """
      |object a {
      | List.em<<>>
      |}
    """.stripMargin,
    label = "empty",
    kind = CompletionItemKind.Method,
    detail = "[A]: List[A]"
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
    label = "TestTrait",
    kind = CompletionItemKind.Interface,
    detail = " extends "
  )

  check(
    "object",
    """
      |object testObject
      |object a {
      |  testObj<<>>
      |}
    """.stripMargin,
    label = "testObject",
    kind = CompletionItemKind.Module,
    detail = ""
  )

  check(
    "package",
    """
      |import scala.collect<<>>
    """.stripMargin,
    label = "collection",
    kind = CompletionItemKind.Module,
    detail = ""
  )

  check(
    "sorting",
    """
      |case class User(name: String, age: Int) {
      |  val someVal = 42
      |  def someMethod(x: String) = x
      |}
      |object a {
      |  User("test", 42).<<>>
      |}
    """.stripMargin, { completions =>
      val first = completions.items(0)
      assert(first.label == "age")
      assert(first.kind == Some(CompletionItemKind.Field))

      val second = completions.items(1)
      assert(second.label == "name")
      assert(second.kind == Some(CompletionItemKind.Field))

      val third = completions.items(2)
      assert(third.label == "someMethod")
      assert(third.kind == Some(CompletionItemKind.Method))

      val fourth = completions.items(3)
      assert(fourth.label == "someVal")
      assert(fourth.kind == Some(CompletionItemKind.Value))

      val last = completions.items.last
      assert(last.label == "wait")
      assert(last.kind == Some(CompletionItemKind.Method))

    }
  )

}
