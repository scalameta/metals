package tests.feature

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticTokensProvider
import scala.meta.internal.metals.{BuildInfo => V}

import tests.DirectoryExpectSuite
import tests.ExpectTestCase
import tests.InputProperties
import tests.TestScala2Compiler
import tests.TestSemanticTokens

class SemanticTokensScala2ExpectSuite(
) extends DirectoryExpectSuite("semanticTokens") {
  override lazy val input: InputProperties = InputProperties.scala2()

  private val compiler =
    TestScala2Compiler.compiler("tokens", input)(munitExecutionContext) match {
      case Some(pc) => pc
      case _ => fail(s"Could not load ${V.scala213} presentation compiler")
    }

  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file,
        () => {
          val params = CompilerVirtualFileParams(
            file.file.toURI,
            file.code,
            EmptyCancelToken,
          )
          val nodes = compiler.semanticTokens(params).get().asScala.toList

          val tokens = SemanticTokensProvider.provide(
            nodes,
            params,
            isScala3 = false,
          )

          TestSemanticTokens.semanticString(
            file.code,
            tokens.toList.map(_.toInt),
          )
        },
      )
    }
  }

  override def afterAll(): Unit = {
    compiler.shutdown()
  }
}
