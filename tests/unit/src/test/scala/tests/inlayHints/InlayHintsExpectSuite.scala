package tests.inlayHints

import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.pc.PresentationCompiler

import tests.BaseInlayHintsExpectSuite
import tests.InputProperties

class InlayHintsExpectSuite
    extends BaseInlayHintsExpectSuite(
      "inlayHints",
      InputProperties.scala2(),
    ) {
  override lazy val compiler: PresentationCompiler =
    new ScalaPresentationCompiler(
      classpath = InputProperties.scala2().classpath.entries.map(_.toNIO)
    )
}
