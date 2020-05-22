package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class CommandPaletteModifier extends StringModifier {
  override val name: String = "command-palette"
  override def process(
      editor: String,
      input: Input,
      reporter: Reporter
  ): String = {
    s"""
       |### Manually trigger build import
       |
       |To manually trigger a build import, execute the "Import build" command through
       |the command palette (`Cmd + Shift + P`).
       |
       |![Import build command](${Image.importCommand(editor)})
       |
       |## Run doctor
       |
       |Execute the "Run Doctor" through the command palette to troubleshoot potential
       |configuration problems in your workspace.
       |
       |![Run doctor command](${Image.runDoctor(editor)})
    """.stripMargin
  }
}
