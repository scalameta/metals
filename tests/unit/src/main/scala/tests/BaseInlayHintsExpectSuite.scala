package tests

import scala.meta.internal.metals.CompilerInlayHintsParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.pc.PresentationCompiler

abstract class BaseInlayHintsExpectSuite(
    name: String,
    inputProperties: => InputProperties,
) extends DirectoryExpectSuite(name) {
  def compiler: PresentationCompiler

  override lazy val input: InputProperties = inputProperties
  val userConfig: UserConfiguration = UserConfiguration().copy(
    showInferredType = Some("true"),
    showImplicitArguments = true,
    showImplicitConversionsAndClasses = true,
  )

  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file,
        () => {
          val rangeParams = CompilerRangeParams(
            file.file.toURI,
            file.code,
            0,
            file.code.length,
            EmptyCancelToken,
          )
          val pcParams = CompilerInlayHintsParams(
            rangeParams,
            true,
            true,
            true,
            true,
          )
          val inlayHints =
            compiler.inlayHints(pcParams).get().asScala.toList
          TestInlayHints.applyInlayHints(file.code, inlayHints)
        },
      )
    }
  }

  override def afterAll(): Unit = {
    compiler.shutdown()
  }
}
