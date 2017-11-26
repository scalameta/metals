package tests.compiler

import scala.concurrent.ExecutionContext.Implicits.global
import scala.meta.languageserver.compiler.SignatureHelpProvider
import langserver.types.SignatureHelp
import play.api.libs.json.Json

object SignatureHelpTest extends CompilerSuite {

  def check(
      filename: String,
      code: String,
      fn: SignatureHelp => Unit
  ): Unit = {
    targeted(
      filename,
      code, { pos =>
        val obtained = SignatureHelpProvider.signatureHelp(compiler, pos)
        fn(obtained)
      },
      addCursor = false
    )
  }

  def check(
      filename: String,
      code: String,
      expected: String
  ): Unit = {
    check(filename, code, { result =>
      val obtained = Json.prettyPrint(Json.toJson(result))
      assertNoDiff(obtained, expected)
    })
  }

  check(
    "assert",
    """
      |object a {
      |  Predef.assert<<(>>
      |}
    """.stripMargin,
    """
      |{
      |  "signatures" : [ {
      |    "label" : "assert(assertion: Boolean, message: => Any)Unit",
      |    "parameters" : [ {
      |      "label" : "assertion: Boolean"
      |    }, {
      |      "label" : "message: => Any"
      |    } ]
      |  }, {
      |    "label" : "assert(assertion: Boolean)Unit",
      |    "parameters" : [ {
      |      "label" : "assertion: Boolean"
      |    } ]
      |  } ],
      |  "activeParameter" : 0
      |}""".stripMargin
  )

  check(
    "multiarg",
    """
      |object b {
      |  Predef.assert("".substring(1, 2), <<msg>>
      |}
    """.stripMargin,
    """
      |{
      |  "signatures" : [ {
      |    "label" : "assert(assertion: Boolean, message: => Any)Unit",
      |    "parameters" : [ {
      |      "label" : "assertion: Boolean"
      |    }, {
      |      "label" : "message: => Any"
      |    } ]
      |  } ],
      |  "activeParameter" : 1
      |}
    """.stripMargin
  )

  check(
    "no-result",
    """
      |object c {
      |  assert(true)
      |  <<caret>>
      |}
    """.stripMargin, { obtained =>
      assert(obtained.signatures.isEmpty)
    }
  )

  check(
    "tricky-comma",
    """
      |object d {
      |  Predef.assert(","<<caret>>
      |}
    """.stripMargin, { obtained =>
      val activeParameter = obtained.activeParameter
      assert(activeParameter.nonEmpty)
      // TODO(olafur) should be 0 since the comma is quoted
      // we can fix this if we use the tokenizer, but then we have to handle
      // other tricky cases like unclosed string literals.
      assert(activeParameter.get == 1)
    }
  )

  check(
    "apply",
    """
      |case class User(name: String, age: Int)
      |object Main {
      |  User("John", <<caret>>
      |}
    """.stripMargin,
    """
      |{
      |  "signatures" : [ {
      |    "label" : "apply(name: String, age: Int)User",
      |    "parameters" : [ {
      |      "label" : "name: String"
      |    }, {
      |      "label" : "age: Int"
      |    } ]
      |  } ],
      |  "activeParameter" : 1
      |}
    """.stripMargin
  )

  check(
    "apply2",
    """
      |object Main {
      |  List(<<1>>
      |}
    """.stripMargin,
    """
      |{
      |  "signatures" : [ {
      |    "label" : "apply[A](xs: A*)List[A]",
      |    "parameters" : [ {
      |      "label" : "xs: A*"
      |    } ]
      |  } ],
      |  "activeParameter" : 0
      |}
    """.stripMargin
  )

  check(
    "apply3",
    """
      |object Main {
      |  List[Int](<<1>>
      |}
    """.stripMargin,
    """
      |{
      |  "signatures" : [ {
      |    "label" : "apply[A](xs: A*)List[A]",
      |    "parameters" : [ {
      |      "label" : "xs: A*"
      |    } ]
      |  } ],
      |  "activeParameter" : 0
      |}
    """.stripMargin
  )

  // The PC doesn't seem to be able to discover this one here, there is
  // no attached symbol to `Process`.
  check(
    "apply4",
    """
      |object Main {
      |  scala.sys.Process(<<1>>
      |}
    """.stripMargin,
    """
      |{
      |  "signatures" : [ ],
      |  "activeParameter" : 0
      |}
    """.stripMargin
  )

  check(
    "constructor",
    """
      |class User(name: String, age: Int) {
      |  def this(name: String) = this(name, 42)
      |}
      |object Main {
      |  new User(<<caret>>
      |}
    """.stripMargin,
    """
      |{
      |  "signatures" : [ {
      |    "label" : "<init>(name: String)User",
      |    "parameters" : [ {
      |      "label" : "name: String"
      |    } ]
      |  }, {
      |    "label" : "<init>(name: String, age: Int)User",
      |    "parameters" : [ {
      |      "label" : "name: String"
      |    }, {
      |      "label" : "age: Int"
      |    } ]
      |  } ],
      |  "activeParameter" : 0
      |}
    """.stripMargin
  )

  check(
    "vararg",
    """
      |object Main {
      |  List(1, 2, <<3>>
      |}
    """.stripMargin, { result =>
      assert(result.activeParameter.contains(0))
    }
  )

}
