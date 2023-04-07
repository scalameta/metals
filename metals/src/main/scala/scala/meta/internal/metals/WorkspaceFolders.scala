package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.logging.MetalsLogger

class WorkspaceFolders(
    initialFolders: List[Folder],
    createService: Folder => MetalsLspService,
    onInitialize: MetalsLspService => Future[Unit],
    shoutdownMetals: () => Future[Unit],
    redirectSystemOut: Boolean,
)(implicit ec: ExecutionContext) {

  private val folderServices: AtomicReference[List[MetalsLspService]] =
    new AtomicReference(initialFolders.map(createService))

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

    val newServices = toAdd.map { folder =>
      val newService = createService(folder)
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
      MetalsLogger.setupLspLogger(
        folderServices.get().map(_.folder),
        redirectSystemOut,
      )
      for {
        _ <- Future.sequence(
          newServices.filterNot(isIn(prev, _)).map(onInitialize)
        )
        _ <- Future(prev.filter(shouldBeRemoved).foreach(_.onShutdown()))
      } yield ()
    }
  }

}
