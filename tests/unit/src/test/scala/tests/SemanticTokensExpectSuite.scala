package tests

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.ScalaPresentationCompiler

class SemanticTokensExpectSuite extends DirectoryExpectSuite("semanticTokens") {

  override lazy val input: InputProperties = InputProperties.scala2()
  private val compiler = new ScalaPresentationCompiler()

  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file,
        () => {
          val tokens = compiler
            .semanticTokens(
              CompilerVirtualFileParams(
                file.file.toURI,
                file.code,
                EmptyCancelToken,
              )
            )
            .get()
          TestSemanticTokens.semanticString(
            file.code,
            tokens.asScala.toList.map(_.toInt),
          )
        },
      )
    }
  }

  override def afterAll(): Unit = {
    compiler.shutdown()
  }
}
