package scala.meta.internal.pantsbuild.commands

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.util.Try
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import scala.util.Failure
import scala.util.Success
import scala.meta.internal.pantsbuild.Export
import scala.meta.internal.pantsbuild.BloopPants
import scala.meta.internal.pantsbuild.MessageOnlyException
import scala.meta.internal.pantsbuild.IntelliJ
import metaconfig.cli.CliApp
import metaconfig.internal.Levenshtein
import scala.meta.internal.pc.LogMessages

object SharedCommand {
  def interpretExport(export: Export): Int = {
    if (!export.pants.isFile) {
      export.app.error(
        s"no Pants build detected, file '${export.pants}' does not exist. " +
          s"To fix this problem, change the working directory to the root of a Pants build."
      )
      1
    } else if (export.isRegenerate) {
      BloopPants.bloopRegenerate(
        AbsolutePath(export.workspace),
        export.targets
      )(ExecutionContext.global)
      0
    } else {
      val workspace = export.workspace
      val targets = export.targets
      val timer = new Timer(Time.system)
      val installResult =
        BloopPants.bloopInstall(export)(ExecutionContext.global)
      installResult match {
        case Failure(exception) =>
          exception match {
            case MessageOnlyException(message) =>
              export.app.error(message)
            case _ =>
              export.app.error(s"fastpass failed to run")
              exception.printStackTrace(export.app.out)
          }
          1
        case Success(count) =>
          IntelliJ.writeBsp(export.project)
          val targets = LogMessages.pluralName("Pants target", count)
          export.app.info(
            s"exported ${targets} to project '${export.project.name}' in $timer"
          )
          LinkCommand.runSymblinkOrWarn(
            export.project,
            export.common,
            export.app,
            isStrict = false
          )
          if (export.open.isEmpty) {
            OpenCommand.onEmpty(export.project, export.app)
          } else {
            OpenCommand.run(
              export.open.withProject(export.project),
              export.app
            )
          }
          0
      }
    }
  }

  def withOneProject(
      action: String,
      projects: List[String],
      common: SharedOptions,
      app: CliApp
  )(fn: Project => Int): Int =
    projects match {
      case Nil =>
        app.error(s"no projects to $action")
        1
      case name :: Nil =>
        Project.fromName(name, common) match {
          case Some(project) =>
            fn(project)
          case None =>
            SharedCommand.noSuchProject(name, app, common)
        }
      case projects =>
        app.error(
          s"expected 1 project to $action but received ${projects.length} arguments '${projects.mkString(" ")}'"
        )
        1
    }

  def noSuchProject(name: String, app: CliApp, common: SharedOptions): Int = {
    val candidates = Project.names(common)
    val closest = Levenshtein.closestCandidate(name, candidates)
    val didYouMean = closest match {
      case Some(candidate) => s"\n\tDid you mean '$candidate'?"
      case None => ""
    }
    app.error(s"project '$name' does not exist$didYouMean")
    1
  }

  def interpretRefresh(refresh: RefreshOptions): Int = { 1 }
}

case class ProjectRoot(
    root: AbsolutePath
) {
  val bspRoot: AbsolutePath = root.resolve(root.filename)
  val bspJson: AbsolutePath = bspRoot.resolve(".bsp").resolve("bloop.json")
  val bloopRoot: AbsolutePath = bspRoot.resolve(".bloop")
}

case class Project(
    common: SharedOptions,
    name: String,
    targets: List[String],
    root: ProjectRoot
) {
  def parentRoot: AbsolutePath = root.root
  def bspRoot: AbsolutePath = root.bspRoot
}
object Project {
  def create(
      name: String,
      common: SharedOptions,
      targets: List[String]
  ): Project = {
    Project(common, name, targets, ProjectRoot(common.home.resolve(name)))
  }
  def names(common: SharedOptions): List[String] =
    fromCommon(common).map(_.name)
  def fromName(
      name: String,
      common: SharedOptions
  ): Option[Project] =
    fromCommon(common, _ == name).headOption
  def fromCommon(
      common: SharedOptions,
      isEnabled: String => Boolean = _ => true
  ): List[Project] = {
    for {
      project <- common.home.list.toBuffer[AbsolutePath].toList
      if (isEnabled(project.filename))
      root = ProjectRoot(project)
      if (root.bspJson.isFile)
      json <- Try(ujson.read(root.bspJson.readText)).toOption
      targets <- json.obj.get("pantsTargets")
    } yield Project(
      common,
      project.filename,
      targets.arr.map(_.str).toList,
      root
    )
  }
}
