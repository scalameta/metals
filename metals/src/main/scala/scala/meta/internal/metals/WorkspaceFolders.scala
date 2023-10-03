package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.logging.MetalsLogger

class WorkspaceFolders(
    initialFolders: List[Folder],
    createService: Folder => MetalsLspService,
    shutdownMetals: () => Future[Unit],
    redirectSystemOut: Boolean,
    initialServerConfig: MetalsServerConfig,
    syncUserConfiguration: List[MetalsLspService] => Future[Unit],
)(implicit ec: ExecutionContext) {

  private val folderServices: AtomicReference[WorkspaceFoldersServices] = {
    val (scalaProjects, nonScalaProjects) =
      initialFolders.partition(_.isMetalsProject) match {
        case (Nil, nonScala) => (List(nonScala.head), nonScala.tail)
        case t => t
      }
    val services = scalaProjects.map(createService(_))
    new AtomicReference(WorkspaceFoldersServices(services, nonScalaProjects))
  }

  def getFolderServices: List[MetalsLspService] = folderServices.get().services
  def nonScalaProjects: List[Folder] = folderServices.get().nonScalaFolders

  def changeFolderServices(
      toRemove: List[Folder],
      toAdd: List[Folder],
  ): Future[Unit] = {
    val actualToRemove =
      toRemove.filterNot(folder => toAdd.exists(_.path == folder.path))
    def shouldBeRemoved(folder: Folder) =
      actualToRemove.exists(_.path == folder.path)

    val (newScala, newNonScala) = toAdd.partition(_.isMetalsProject)
    val newServices = newScala.map(createService(_))
    if (newServices.isEmpty && getFolderServices.forall(shouldBeRemoved)) {
      shutdownMetals()
    } else {
      val WorkspaceFoldersServices(prev, _) =
        folderServices.getAndUpdate {
          case WorkspaceFoldersServices(services, nonScalaProjects) =>
            val updatedServices =
              services.filterNot(shouldBeRemoved) ++
                newServices.filterNot(isIn(services, _))
            val updatedNonScala =
              nonScalaProjects.filterNot(shouldBeRemoved) ++
                newNonScala.filterNot(isIn(nonScalaProjects ++ services, _))
            WorkspaceFoldersServices(
              updatedServices,
              updatedNonScala,
            )
        }

      setupLogger()

      val services = newServices.filterNot(isIn(prev, _))
      for {
        _ <- syncUserConfiguration(services)
        _ <- Future.sequence(services.map(_.initialized()))
        _ <- Future(prev.filter(shouldBeRemoved).foreach(_.onShutdown()))
      } yield ()
    }
  }

  def convertToScalaProject(folder: Folder): MetalsLspService = {
    val newService = createService(folder)
    val WorkspaceFoldersServices(prev, _) = folderServices.getAndUpdate {
      case wfs @ WorkspaceFoldersServices(services, nonScalaProjects) =>
        if (!isIn(services, folder)) {
          WorkspaceFoldersServices(
            services :+ newService,
            nonScalaProjects.filterNot(_ == folder),
          )
        } else wfs
    }

    prev.find(_.path == folder.path) match {
      case Some(service) => service
      case None =>
        setupLogger()
        for {
          _ <- syncUserConfiguration(List(newService))
        } newService.initialized()
        newService
    }
  }

  private def setupLogger() =
    MetalsLogger.setupLspLogger(
      getFolderServices.map(_.path),
      redirectSystemOut,
      initialServerConfig,
    )

  private def isIn(services: List[Folder], service: Folder) =
    services.exists(_.path == service.path)

}

case class WorkspaceFoldersServices(
    services: List[MetalsLspService],
    nonScalaFolders: List[Folder],
)
