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
       |**Java 8**. Metals does not work with Java 11 yet so make sure the JAVA_HOME
       |environment variable points to Java 8.
       |
       |**macOS, Linux or Windows**. Metals is developed on macOS and every PR is
       |tested on Ubuntu+Windows.
       |
       |**Scala 2.12 and 2.11**. Metals works only with Scala versions
       |2.12.{7,6,5,4} and 2.11.{12,11,10,9}.
       |Note that 2.10.x and 2.13.0-M5 are not supported.
       |""".stripMargin
  }
}
