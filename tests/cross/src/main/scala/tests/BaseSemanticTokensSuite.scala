package tests

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerVirtualFileParams

import munit.Location
import munit.TestOptions
import tests.BasePCSuite
import tests.TestSemanticTokens

class BaseSemanticTokensSuite extends BasePCSuite {

  def check(
      name: TestOptions,
      expected: String,
      compat: Map[String, String] = Map.empty,
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
        getExpected(expected, compat, scalaVersion),
      )

    }
}
