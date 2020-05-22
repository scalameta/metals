package scala.meta.internal.pantsbuild.commands

import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pantsbuild.PantsConfiguration
import scala.meta.io.AbsolutePath

import ujson.Bool

case class Project(
    common: SharedOptions,
    name: String,
    targets: List[String],
    root: ProjectRoot,
    sources: Boolean
) {
  val fuzzyName: String = PantsConfiguration.outputFilename(name)
  def matchesName(query: String): Boolean =
    Project.matchesFuzzyName(query, name, fuzzyName)
  def bspRoot: AbsolutePath = root.bspRoot
}

object Project {
  def create(
      name: String,
      common: SharedOptions,
      targets: List[String],
      sources: Boolean
  ): Project = {
    Project(
      common,
      name,
      targets,
      ProjectRoot(common.home.resolve(name)),
      sources
    )
  }
  def names(common: SharedOptions): List[String] =
    fromCommon(common).map(_.name)

  def matchesFuzzyName(
      query: String,
      projectName: String,
      fuzzyProjectName: String
  ): Boolean =
    projectName == query ||
      fuzzyProjectName == query

  def fromName(
      name: String,
      common: SharedOptions
  ): Option[Project] = {
    val fuzzyName = PantsConfiguration.outputFilename(name)
    fromCommon(
      common,
      { candidate =>
        matchesFuzzyName(candidate, name, fuzzyName)
      }
    ).headOption
  }
  def fromCommon(
      common: SharedOptions,
      isEnabled: String => Boolean = _ => true
  ): List[Project] = {
    for {
      project <- common.home.list.toBuffer[AbsolutePath].toList
      if isEnabled(project.filename)
      root = ProjectRoot(project)
      if root.bspJson.isFile
      json <- Try(ujson.read(root.bspJson.readText)).toOption
      targets <- json.obj.get("pantsTargets")
      sources = json.obj.get("sources") match {
        case Some(Bool(true)) => true
        case _ => false
      }
    } yield Project(
      common,
      project.filename,
      targets.arr.map(_.str).toList,
      root,
      sources
    )
  }

}
