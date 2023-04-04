package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BuildTools

class WorkspaceFolders(
    initialFolders: List[Folder],
    createService: Folder => MetalsLspService,
    onInitialize: MetalsLspService => Future[Unit],
    shoutdownMetals: () => Future[Unit],
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
  ): Future[Unit] = {
    val actualToRemove =
      toRemove.filterNot(folder => toAdd.exists(_.uri == folder.uri))
    def shouldBeRemoved(service: MetalsLspService) =
      actualToRemove.exists(_.uri == service.folder)
    def isIn(services: List[MetalsLspService], service: MetalsLspService) =
      services.exists(_.folder == service.folder)

    val newServices = createServices(toAdd).map { newService =>
      newService.loadFingerPrints()
      newService.registerNiceToHaveFilePatterns()
      newService.connectTables()
      newService
    }
    if (newServices.isEmpty && getFolderServices.forall(shouldBeRemoved)) {
      shoutdownMetals()
    } else {
      val prev =
        folderServices.getAndUpdate { current =>
          val afterRemove = current.filterNot(shouldBeRemoved)
          val newToAdd = newServices.filterNot(isIn(current, _))
          afterRemove ++ newToAdd
        }
      for {
        _ <- Future.sequence(
          newServices.filterNot(isIn(prev, _)).map(onInitialize)
        )
        _ <- Future(prev.filter(shouldBeRemoved).foreach(_.onShutdown()))
      } yield ()
    }
  }

  private def createServices(folders: List[Folder]): List[MetalsLspService] =
    folders
      .withFilter { case Folder(uri, _) => !BuildTools.default(uri).isEmpty }
      .map(createService)

}
