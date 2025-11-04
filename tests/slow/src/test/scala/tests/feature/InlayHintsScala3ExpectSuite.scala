package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.pc.PresentationCompiler

import tests.BaseInlayHintsExpectSuite
import tests.InputProperties
import tests.TestScala3Compiler

@munit.IgnoreSuite
class InlayHintsScala3ExpectSuite(
) extends BaseInlayHintsExpectSuite(
      "inlayHints3",
      InputProperties.scala3(),
    ) {
  override val compiler: PresentationCompiler = {
    TestScala3Compiler.compiler("inlayHints", input)(
      munitExecutionContext
    ) match {
      case Some(pc) => pc
      case _ => fail(s"Could not load ${V.scala3} presentation compiler")
    }

  }
}
