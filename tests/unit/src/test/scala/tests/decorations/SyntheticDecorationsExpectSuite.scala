package tests.decorations

import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.pc.PresentationCompiler

import tests.BaseSyntheticDecorationsExpectSuite
import tests.InputProperties

class SyntheticDecorationsExpectSuite
    extends BaseSyntheticDecorationsExpectSuite(
      "decorations",
      InputProperties.scala2(),
    ) {
  override val compiler: PresentationCompiler = new ScalaPresentationCompiler(
    classpath = InputProperties.scala2().classpath.entries.map(_.toNIO)
  )
}
