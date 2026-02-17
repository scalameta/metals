package tests.pc

import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.SemanticTokensProviderV2

import munit.Location
import munit.TestOptions
import tests.TestSemanticTokens

class BaseProtoSemanticTokensSuite extends BaseProtoPCSuite {

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val params =
        CompilerVirtualFileParams(
          Paths.get("SemanticTokens.proto").toUri(),
          original,
        )
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

  def checkNodes(
      testOpt: TestOptions,
      original: String,
      expected: String,
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val params =
        CompilerVirtualFileParams(
          Paths.get("SemanticTokens.proto").toUri(),
          original,
        )
      val nodes = presentationCompiler.semanticTokens(params).get()

      val obtained =
        TestSemanticTokens.pcSemanticString(original, nodes.asScala.toList)

      assertNoDiff(
        obtained,
        expected,
      )
    }
  }
}
