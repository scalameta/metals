package scala.meta.internal.pantsbuild.commands

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.util.Try

case class Project(
    common: SharedOptions,
    name: String,
    targets: List[String],
    root: ProjectRoot
) {
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
      if isEnabled(project.filename)
      root = ProjectRoot(project)
      if root.bspJson.isFile
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
