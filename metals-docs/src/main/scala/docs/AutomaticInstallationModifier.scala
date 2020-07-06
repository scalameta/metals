package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class AutomaticInstallationModifier extends StringModifier {
  val name = "automatic-installation"

  override def process(info: String, code: Input, reporter: Reporter): String =
    s"""
       |## Automatic installation
       |
       |The first time you open Metals in a new $info workspace you will be
       |prompted to import the build. Select "Import Build" to start the
       |automatic installation. This will create all the needed Bloop config
       |files. You should then be able to edit and compile your code utilizing
       |all of the features.
       |""".stripMargin
}
