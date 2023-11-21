package tests

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerVirtualFileParams

import munit.Location
import munit.TestOptions

class BaseSemanticTokensSuite extends BasePCSuite {

  // We check only if correct symbol tokens are added here.
  // Other tokens (e.g. keywords) are added outside the compiler.
  def check(
      name: TestOptions,
      expected: String,
      compat: Map[String, String] = Map.empty
  )(implicit location: Location): Unit =
    test(name) {
      val base =
        expected
          .replaceAll(raw"/\*[\w,]+\*/", "")
          .replaceAll(raw"\<\<|\>\>", "")
      val nodes = presentationCompiler
        .semanticTokens(
          CompilerVirtualFileParams(URI.create("file:/Tokens.scala"), base)
        )
        .get()

      val obtained = TestSemanticTokens.pcSemanticString(
        base,
        nodes.asScala.toList
      )
      assertEquals(
        obtained,
        getExpected(expected, compat, scalaVersion)
      )

    }
}
