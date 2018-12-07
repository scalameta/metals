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
       |The first time you open Metals in a new workspace it prompts you to import the build.
       |Click "Import build" to start the installation step.
       |
       |![Import build](../assets/$editor-import-build.png)
       |
       |- "Not now" disables this prompt for 2 minutes.
       |- "Don't show again" disables this prompt forever, use `rm -rf .metals/` to re-enable
       |  the prompt.
       |- Behind the scenese, Metals uses [Bloop](https://scalacenter.github.io/bloop/) to
       |  import sbt builds, but you don't need Bloop installed on your machine to run this step.
       |
       |Once the import step completes, compilation starts for your open `*.scala`
       |files.
       |
       |Once the sources have compiled successfully, you can navigate the codebase with
       |"goto definition" with `Cmd+Click`.
       |
       |### Speeding up import
       |
       |The "Import build" step can take a long time, especially the first time you
       |run it in a new build. The exact time depends on the complexity of the build and
       |if library dependencies need to be downloaded. For example, this step can take
       |everything from 10 seconds in small cached builds up to 10-15 minutes in large
       |uncached builds.
       |
       |Consult the [Bloop documentation](https://scalacenter.github.io/bloop/docs/build-tools/sbt#speeding-up-build-export)
       |to learn how to speed up build import.
       |
       |### Importing changes
       |
       |When you change `build.sbt` or sources under `project/`, you will be prompted to
       |re-import the build.
       |
       |![Import sbt changes](assets/$editor-import-changes.png)
       |
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
