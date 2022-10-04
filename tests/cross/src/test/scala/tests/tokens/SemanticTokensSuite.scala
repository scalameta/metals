package tests.tokens

import tests.BasePCSuite
import munit.TestOptions
import munit.Location
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.jdk.CollectionConverters._
import java.net.URI
import tests.TestSemanticTokens

class SemanticTokensSuite extends BasePCSuite {

  check(
    "deprecated",
    s"""|<<object>>/*keyword*/ <<sample9>>/*class*/ {
        |  <<@>>/*keyword*/<<deprecated>>/*class*/(<<"this method will be removed">>/*string*/, <<"FooLib 12.0">>/*string*/)
        |  <<def>>/*keyword*/ <<oldMethod>>/*method,deprecated*/(<<x>>/*parameter*/: <<Int>>/*class,abstract*/) = <<x>>/*parameter*/
        |
        |  <<def>>/*keyword*/ <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[String]) ={
        |    <<val>>/*keyword*/ <<str>>/*variable,readonly*/ = <<oldMethod>>/*method,deprecated*/(<<2>>/*number*/).<<toString>>/*method*/
        |     <<println>>/*method*/(<<"Hello, world!">>/*string*/<<+>>/*method*/ <<str>>/*variable,readonly*/)
        |  }
        |}
        |""".stripMargin,
  )

  def check(
      name: TestOptions,
      expected: String,
  )(implicit location: Location): Unit =
    test(name) {

      val base =
        expected
          .replaceAll(raw"/\*[\w,]+\*/", "")
          .replaceAll(raw"\<\<|\>\>", "")

      val tokens = presentationCompiler
        .semanticTokens(
          CompilerVirtualFileParams(URI.create("file:/Tokens.scala"), base)
        )
        .get()

      val obtained = TestSemanticTokens.semanticString(
        base,
        tokens.asScala.toList.map(_.toInt),
      )
      assertEquals(
        obtained,
        expected,
      )

    }
}
