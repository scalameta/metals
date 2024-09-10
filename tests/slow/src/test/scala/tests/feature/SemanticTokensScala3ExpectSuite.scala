package tests.feature

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticTokensProvider
import scala.meta.internal.metals.{BuildInfo => V}

import tests.DirectoryExpectSuite
import tests.ExpectTestCase
import tests.InputProperties
import tests.MetalsTestEnrichments
import tests.TestScala3Compiler
import tests.TestSemanticTokens

class SemanticTokensScala3ExpectSuite(
) extends DirectoryExpectSuite("semanticTokens3") {
  override lazy val input: InputProperties = InputProperties.scala3()
  private val compiler =
    TestScala3Compiler.compiler("tokens", input)(munitExecutionContext) match {
      case Some(pc) => pc
      case _ => fail(s"Could not load ${V.scala3} presentation compiler")
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
            file.file,
            isScala3 = true,
            MetalsTestEnrichments.emptyTrees,
          )(EmptyReportContext)

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
