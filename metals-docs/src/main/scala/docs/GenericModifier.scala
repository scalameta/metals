package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input

class GenericModifier extends StringModifier {
  override val name: String = "generic"

  override def process(info: String, code: Input, reporter: Reporter): String =
    s"""
       |## Gitignore `project/metals.sbt` `.metals/` and `.bloop/`
       |
       |The Metals server places logs and other files in the `.metals/` directory. The
       |Bloop compile server places logs and compilation artifacts in the `.bloop`
       |directory. Bloop plugin that generates Bloop configuration is added in the 
       |`project/metals.sbt` file. It's recommended to ignore these directories and file
       |from version control systems like git.
       |
       |```sh
       |# ~/.gitignore
       |.metals/
       |.bloop/
       |project/metals.sbt
       |```
       |
     """.stripMargin
}
