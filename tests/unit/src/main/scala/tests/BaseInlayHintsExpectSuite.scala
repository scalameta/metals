package tests

import scala.meta.internal.metals.CompilerInlayHintsParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.InlayHintCompat
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.PresentationCompiler

abstract class BaseInlayHintsExpectSuite(
    name: String,
    inputProperties: => InputProperties,
) extends DirectoryExpectSuite(name) {
  def compiler: PresentationCompiler

  override lazy val input: InputProperties = inputProperties

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
            true,
            true,
            true,
            true,
          )
          val inlayHints =
            compiler
              .inlayHints(pcParams)
              .get()
              .asScala
              .toList
              .map(
                InlayHintCompat
                  .maybeFixInlayHintData(_, file.file.toURI.toString())
              )
          TestInlayHints.applyInlayHints(
            file.code,
            inlayHints,
            withTooltip = false,
          )
        },
      )
    }
  }

  override def afterAll(): Unit = {
    compiler.shutdown()
  }
}
