package scala.meta.internal.metals

import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ujson.Value

object DelegateSetting {
  val delegateSetting = "delegate"
  private val projectRefSetting = "project-ref"

  def writeProjectRef(
      folder: AbsolutePath,
      projectRefs: List[AbsolutePath],
  ): Unit = {
    val relPath = projectRefs.map(_.toRelative(folder))
    val jsonText =
      ujson.Obj(projectRefSetting -> relPath.map(_.toString())).toString()
    folder.resolve(Directories.metalsSettings).writeText(jsonText)
  }

  def readProjectRefs(folder: AbsolutePath): List[AbsolutePath] = {
    for {
      setting <- getSetting(folder, projectRefSetting).toList
      ref <- setting.arrOpt.getOrElse(Nil)
      pathStr <- ref.strOpt
      path = folder.resolve(pathStr).dealias
      if path.exists
    } yield path
  }

  def readDeleteSetting(root: AbsolutePath): Option[AbsolutePath] =
    for {
      setting <- getSetting(root, delegateSetting)
      relPath <- setting.strOpt
      path = root.resolve(relPath).dealias
      if path.exists
    } yield path

  private def getSetting(
      root: AbsolutePath,
      settingName: String,
  ): Option[Value] =
    for {
      text <- root.resolve(Directories.metalsSettings).readTextOpt
      setting <- Try(ujson.read(text)(settingName)).toOption
    } yield setting

}
