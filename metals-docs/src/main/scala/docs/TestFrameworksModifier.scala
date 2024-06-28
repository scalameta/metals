package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class TestFrameworksModifier extends StringModifier {
  override val name: String = "test-frameworks"

  override def process(info: String, code: Input, reporter: Reporter): String =
    """|NOTE: While Metals detects test suites for most of existing testing
       |frameworks, support for recognizing individual tests is more limited.
       |Metals supports the current set of test frameworks when it comes to
       |individual test discovery:
       |
       | - Junit
       | - MUnit
       | - Scalatest
       | - Weaver Test 
       |""".stripMargin

}
