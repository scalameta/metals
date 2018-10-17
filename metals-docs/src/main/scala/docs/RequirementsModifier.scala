package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input

class RequirementsModifier extends StringModifier {
  override val name: String = "requirements"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    s"""
       |## Requirements
       |
       |* Java 8. Metals does not yet work with Java 11, see [scalameta/scalameta#1779](https://github.com/scalameta/scalameta/issues/1779)
       |* macOS, Linux or Windows. Metals is not developed on Windows but CI runs tests on Windows so
       |  please report issues if you encounter problems.
     """.stripMargin
  }
}
