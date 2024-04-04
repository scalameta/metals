package scala.meta.internal.metals

import scala.meta.io.AbsolutePath

class DelegatingFolderService(
    folder: AbsolutePath,
    folderVisibleName: Option[String],
    val service: MetalsLspService,
) extends Folder(folder, folderVisibleName, isKnownMetalsProject = true)
    with FolderService
