package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class TextEditorModifier extends StringModifier {
  val name = "editor"

  override def process(
      editor: String,
      input: Input,
      reporter: Reporter
  ): String = {
    val autoImportNote = if (editor == "emacs") {
      """Type "Import build" or press `Tab` and select "Import build""""
    } else {
      """Click "Import build""""
    }
    val sections = input.text.split("---+").lift.andThen(_.filterNot(_.isEmpty))
    val sbtLauncher = sections(0).getOrElse(
      """
        |Update the server property `-Dmetals.sbt-script=/path/to/sbt` to use a custom
        |`sbt` script instead of the default Metals launcher if you need further
        |customizations like reading environment variables.
      """.stripMargin
    )
    s"""
       |## Importing a build
       |
       |The first time you open Metals in a new workspace it prompts you to import the build.
       |${autoImportNote} to start the installation step.
       |
       |![Import build](${Image.importBuild(editor)})
       |
       |- "Not now" disables this prompt for 2 minutes.
       |- "Don't show again" disables this prompt forever, use `rm -rf .metals/` to re-enable
       |  the prompt.
       |- Use `tail -f .metals/metals.log` to watch the build import progress.
       |- Behind the scenes, Metals uses [Bloop](https://scalacenter.github.io/bloop/) to
       |  import sbt builds, but you don't need Bloop installed on your machine to run this step.
       |
       |Once the import step completes, compilation starts for your open `*.scala`
       |files.
       |
       |Once the sources have compiled successfully, you can navigate the codebase with
       |goto definition.
       |
       |### Custom sbt launcher
       |
       |By default, Metals runs an embedded `sbt-launch.jar` launcher that respects `.sbtopts` and `.jvmopts`.
       |However, the environment variables `SBT_OPTS` and `JAVA_OPTS` are not respected.
       |
       |$sbtLauncher
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
       |![Import sbt changes](${Image.importChanges(editor)})
       |
    """.stripMargin
  }
}
