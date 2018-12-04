package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input

class TextEditorModifier extends StringModifier {
  val name = "editor"
  override def process(
      editor: String,
      code: Input,
      reporter: Reporter
  ): String = {
    s"""
       |## Importing a build
       |
       |The first time you open Metals in a new sbt build it prompts you to "Import
       |build via Bloop". Click "Import build via Bloop" to start the `sbt bloopInstall`
       |import step.
       |
       |![Import build](../assets/$editor-import-via-bloop.png)
       |
       |- you don't need Bloop installed on your machine to run this step.
       |- this step is required for compile errors and goto definition to work.
       |
       |The `sbt bloopInstall` step can take a long time, especially the first time you
       |run it in a new build. The exact time depends on the complexity of the build and
       |if library dependencies are cached or need to be downloaded. For example, this
       |step can take everything from 10 seconds in small cached builds up to 10-15
       |minutes in large uncached builds.
       |
       |Once the import step completes, compilation starts for your open `*.scala`
       |files.
       |
       |Once the sources have compiled successfully, you can navigate the codebase with
       |"goto definition" by `Cmd+Click` or `Cmd+Shift+Enter`.
       |
       |When you change `build.sbt` or sources under `project/`, you will be prompted to
       |re-import the build.
       |
       |![Import sbt changes](assets/$editor-import-changes.png)
       |
       |Click "Import changes" and that will restart the `sbt bloopInstall` step. Select
       |"Don't show again" if you prefer to manually trigger build import.
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
