package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.pc.PresentationCompiler

import tests.BaseSyntheticDecorationsExpectSuite
import tests.InputProperties
import tests.TestScala3Compiler

class SyntheticDecorationsScala3ExpectSuite(
) extends BaseSyntheticDecorationsExpectSuite(
      "decorations3",
      InputProperties.scala3(),
    ) {
  override val compiler: PresentationCompiler = {
    TestScala3Compiler.compiler("decorations", input)(
      munitExecutionContext
    ) match {
      case Some(pc) => pc
      case _ => fail(s"Could not load ${V.scala3} presentation compiler")
    }

  }
}
