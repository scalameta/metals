package scala.meta.internal.metals

import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

class DelegatingFolderService(
    folder: AbsolutePath,
    folderVisibleName: Option[String],
    val service: MetalsLspService,
) extends Folder(folder, folderVisibleName, isKnownMetalsProject = true)
    with FolderService {

  def writeSetting(): Unit = {
    try {
      DelegateSetting.writeDeleteSetting(folder, service.path)
    } catch {
      case NonFatal(_) =>
    }
  }

}

object DelegatingFolderService {
  def apply(folder: Folder, service: MetalsLspService) =
    new DelegatingFolderService(folder.path, folder.visibleName, service)
}

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
