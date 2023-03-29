package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.MetalsEnrichments._

class WorkspaceFolders(
    initialFolders: List[Folder],
    createService: Folder => MetalsLspService,
    workspaceLspService: WorkspaceLspService,
)(implicit ec: ExecutionContext) {

  private val folderServices: AtomicReference[List[MetalsLspService]] = {
    val res = createServices(initialFolders)
    if (res.isEmpty)
      new AtomicReference(List(createService(initialFolders.head)))
    else new AtomicReference(res)
  }

  def getFolderServices: List[MetalsLspService] = folderServices.get()

  def changeFolderServices(
      toRemove: List[Folder],
      toAdd: List[Folder],
  ): Future[Unit] =
    Future
      .sequence(toAdd.map(addFolder))
      .flatMap(_ => Future.sequence(toRemove.map(removeFolder)))
      .ignoreValue

  private def removeFolder(folder: Folder) = Future {
    val path = folder.uri
    val prev = folderServices.getAndUpdate(current =>
      current.filterNot(_.folder == path)
    )
    prev.filter(_.folder == path).foreach(_.onShutdown())
  }

  private def addFolder(folder: Folder) =
    for {
      newService <- Future {
        val newService = createService(folder)
        newService.loadFingerPrints()
        newService.registerNiceToHaveFilePatterns()
        newService.connectTables()
        newService
      }
      _ <- newService.initialized(workspaceLspService).map { _ =>
        val prev = folderServices.getAndUpdate(current =>
          current.filterNot(_.folder == folder.uri) ++ List(newService)
        )
        prev.filter(_.folder == folder.uri).foreach(_.onShutdown())
      }
    } yield ()

  private def createServices(folders: List[Folder]): List[MetalsLspService] = {
    folders
      .withFilter { case Folder(uri, _) => !BuildTools.default(uri).isEmpty }
      .map(createService)
  }

}
