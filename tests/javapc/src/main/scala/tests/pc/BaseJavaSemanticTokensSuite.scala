package tests.pc

import java.net.URI

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.SemanticTokensProviderV2

import munit.Location
import munit.TestOptions
import tests.TestHovers
import tests.TestSemanticTokens

class BaseJavaSemanticTokensSuite extends BaseJavaPCSuite with TestHovers {

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "SemanticTokens.java"
      val pkg = packageName(testOpt.name)
      val code = s"package $pkg;\n$original"

      val params =
        CompilerVirtualFileParams(URI.create(s"file:///$filename"), code)
      val nodes = presentationCompiler.semanticTokens(params).get()
      val tokens =
        new SemanticTokensProviderV2(params, nodes.asScala.toList).provide()

      val obtained =
        TestSemanticTokens.semanticStringV2(params, tokens)

      assertNoDiff(
        obtained,
        expected,
      )
    }
  }

}
