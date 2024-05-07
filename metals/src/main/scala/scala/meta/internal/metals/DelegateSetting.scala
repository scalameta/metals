package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object DelegateSetting {
  private val settingName = "delegate"

  def writeDeleteSetting(
      folder: AbsolutePath,
      servicePath: AbsolutePath,
  ): Unit = {
    val relPath = servicePath.toRelative(folder)
    val jsonText = ujson.Obj(settingName -> relPath.toString()).toString()
    folder.resolve(Directories.metalsSettings).writeText(jsonText)

  }

  def readDeleteSetting(folder: AbsolutePath): Option[AbsolutePath] =
    for {
      text <- folder.resolve(Directories.metalsSettings).readTextOpt
      json = ujson.read(text)
      relPath <- json(settingName).strOpt
      path = folder.resolve(relPath).dealias
      if path.exists
    } yield path

}
