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
       |**Java 8**. Metals does not yet work with Java 11, see
       |[scalameta/scalameta#1779](https://github.com/scalameta/scalameta/issues/1779)
       |
       |**macOS, Linux or Windows**. Metals is developed on macOS and every PR is tested on Ubuntu+Windows.
       |
       |**Scala 2.12.7 and 2.11.12**. Metals works only with the latest Scala 2.12 and 2.11 versions.
       |See [scalameta/scalameta#1695](https://github.com/scalameta/scalameta/issues/1695) for updates on
       |adding support for Scala 2.13 once it's out.
       |""".stripMargin
  }
}
