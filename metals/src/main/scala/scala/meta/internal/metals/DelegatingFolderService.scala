package scala.meta.internal.metals

import scala.meta.io.AbsolutePath

class DelegatingFolderService(
    folder: AbsolutePath,
    folderVisibleName: Option[String],
    val service: MetalsLspService,
) extends Folder(folder, folderVisibleName, isKnownMetalsProject = true)
    with FolderService

object DelegatingFolderService {
  def apply(folder: Folder, service: MetalsLspService) =
    new DelegatingFolderService(folder.path, folder.visibleName, service)
}
