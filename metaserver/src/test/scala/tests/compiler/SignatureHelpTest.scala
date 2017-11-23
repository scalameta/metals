package tests.compiler

import scala.concurrent.ExecutionContext.Implicits.global
import scala.meta.languageserver.compiler.SignatureHelpProvider
import play.api.libs.json.Json

object SignatureHelpTest extends CompilerSuite {

  def check(
      filename: String,
      code: String,
      expected: String
  ): Unit = {
    targeted(
      filename,
      code, { pos =>
        val result = SignatureHelpProvider.signatureHelp(compiler, pos)
        val obtained = Json.prettyPrint(Json.toJson(result))
        assertNoDiff(obtained, expected)
      }
    )
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
      |  } ]
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
      |
    """.stripMargin
  )
}
