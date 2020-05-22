package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class CustomizeBloopModifier extends StringModifier {
  val name = "custom-bloop"

  override def process(info: String, code: Input, reporter: Reporter): String =
    s"""
       |## Customizing build import
       |
       |Consult the Bloop docs for customizing build import for sbt:
       |https://scalacenter.github.io/bloop/docs/build-tools/sbt
       |
       |- Enable `IntegrationTest` and other custom configurations
       |- Speed up build import
       |- Enable sbt project references (source dependencies)
       |- Export main class
       |""".stripMargin
}
