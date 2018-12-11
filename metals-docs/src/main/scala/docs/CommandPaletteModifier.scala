package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input

class CommandPaletteModifier extends StringModifier {
  override val name: String = "command-palette"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    val editor = info
    s"""
       |### Manually trigger build import
       |
       |To manually trigger a build import, execute the "Import build" command through
       |the command palette (`Cmd + Shift + P`).
       |
       |![Import build command](assets/$editor-import-build-command.png)
       |
       |## Run doctor
       |
       |Execute the "Run Doctor" through the command palette to troubleshoot potential
       |configuration problems in your workspace.
       |
       |![Run doctor command](assets/$editor-run-doctor.png)
    """.stripMargin
  }
}
