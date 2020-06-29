package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class GenericModifier extends StringModifier {
  override val name: String = "generic"

  override def process(info: String, code: Input, reporter: Reporter): String =
    s"""
       |## Files and Directories to include in your Gitignore
       |
       |The Metals server places logs and other files in the `.metals` directory. The
       |Bloop compile server places logs and compilation artifacts in the `.bloop`
       |directory. Bloop plugin that generates Bloop configuration is added in the 
       |`project/metals.sbt` file. Working with Ammonite scripts will place
       |compiled scripts into the `.ammonite` directory.
       |It's recommended to exclude these directories and file
       |from version control systems like git.
       |
       |```sh
       |# ~/.gitignore
       |.metals/
       |.bloop/
       |.ammonite/
       |project/metals.sbt
       |```
       |
     """.stripMargin
}
