package scala.meta.internal.pantsbuild.commands

import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import scala.meta.internal.pantsbuild.IntelliJ

import metaconfig.cli.CliApp
import metaconfig.cli.Command
import metaconfig.cli.Messages
import metaconfig.cli.TabCompletionContext
import metaconfig.cli.TabCompletionItem
import org.typelevel.paiges.Doc

object AmendCommand extends Command[AmendOptions]("amend") {
  override def description: Doc =
    Doc.paragraph(
      "Edit the Pants targets of an existing project"
    )
  override def options: Doc = Messages.options(AmendOptions())
  override def complete(
      context: TabCompletionContext
  ): List[TabCompletionItem] =
    SharedCommand.complete(context)
  def run(amend: AmendOptions, app: CliApp): Int = {
    SharedCommand.withOneProject(
      "amend",
      amend.projects,
      amend.common,
      app
    ) { project =>
      amend.targetsToAmend match {
        case Some(value) => {
          runAmend(amend, app, project, value)
        }
        case None => {
          Option(System.getenv("EDITOR")) match {
            case None =>
              app.error(
                "the $EDITOR environment variable is undefined. " +
                  "To fix this problem, run `export EDITOR=vim` " +
                  "(or `export EDITOR='code -w'` for VS Code) " +
                  "and try again"
              )
              1
            case Some(editor) =>
              val list = getTargetsListViaEditor(
                amend,
                app,
                editor,
                project
              )
              list.map(runAmend(amend, app, project, _)).getOrElse(1)
          }
        }
      }

    }

  }

  private def getTargetsListViaEditor(
      amend: AmendOptions,
      app: CliApp,
      editor: String,
      project: Project
  ): Option[List[String]] = {
    val tmp = newTemporaryAmendFile(project)
    val exit = editFile(editor, tmp, app)
    if (exit != 0 && !Files.isRegularFile(tmp)) {
      app.error(s"failed to amend '${project.name}'")
      None
    } else {
      val newTargets = Files
        .readAllLines(tmp)
        .asScala
        .flatMap { line =>
          if (line.startsWith("#")) Nil
          else line.split(" ").toList
        }
        .toList
      Files.deleteIfExists(tmp)
      Some(newTargets)
    }
  }

  private def runAmend(
      amend: AmendOptions,
      app: CliApp,
      project: Project,
      newTargets: Seq[String]
  ): Int = {
    if (newTargets.isEmpty) {
      app.error(
        "aborting amend operation because the new target list is empty." +
          s"\n\tTo delete the project run: fastpass remove ${project.name}"
      )
      1
    } else {
      val newProject = project.copy(targets = newTargets.toList)
      if (newTargets != project.targets) {
        IntelliJ.writeBsp(newProject)
        RefreshCommand.run(
          RefreshOptions(
            projects = amend.projects,
            export = amend.export,
            open = amend.open
          ).withCommon(amend.common),
          app
        )
        0
      } else {
        app.error(
          "aborting amend operation because the target list is unchanged." +
            s"\n\tTo refresh the project run: fastpass refresh ${project.name}"
        )
        1
      }
    }
  }

  def newTemporaryAmendFile(project: Project): Path = {
    Files.write(
      Files.createTempFile("fastpass", s"${project.name}.ini"),
      project.targets
        .mkString(
          "",
          "\n",
          "\n# Please add or remove targets from this list.\n" +
            "# When you're done, save the file and close the editor.\n" +
            "# Lines starting with '#' will be ignored."
        )
        .getBytes(StandardCharsets.UTF_8)
    )
  }

  private def editFile(editor: String, tmp: Path, app: CliApp): Int = {
    try {
      // Adjusted from https://stackoverflow.com/questions/29733038/running-interactive-shell-program-in-java
      val proc = Runtime.getRuntime().exec("/bin/bash")
      val stdin = proc.getOutputStream()
      val pw = new PrintWriter(stdin)
      pw.println(s"$editor $tmp < /dev/tty > /dev/tty")
      pw.close()
      proc.waitFor()
    } catch {
      case NonFatal(e) =>
        app.error(s"failed to edit file with EDITOR='$editor'")
        e.printStackTrace(app.out)
        1
    }
  }
}
