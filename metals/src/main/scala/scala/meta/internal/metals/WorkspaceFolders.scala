package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments.XtensionBuildTarget
import scala.meta.internal.metals.logging.MetalsLogger

class WorkspaceFolders(
    initialFolders: List[Folder],
    createService: Folder => MetalsLspService,
    shutdownMetals: () => Future[Unit],
    redirectSystemOut: Boolean,
    initialServerConfig: MetalsServerConfig,
    userConfigSync: UserConfigurationSync,
)(implicit ec: ExecutionContext) {

  private val folderServices: AtomicReference[WorkspaceFoldersServices] = {
    val (scalaProjects, nonScalaProjects) =
      initialFolders.partition(_.isMetalsProject)
    val services = scalaProjects.map(createService(_))
    new AtomicReference(
      WorkspaceFoldersServices(services, Nil, nonScalaProjects)
    )
  }

  def getFolderServices: List[MetalsLspService] = folderServices.get().services
  def delegatingServices: List[DelegatingFolderService] =
    folderServices.get().delegatingServices
  def nonScalaProjects: List[Folder] = folderServices.get().nonScalaFolders

  def changeFolderServices(
      toRemove: List[Folder],
      toAdd: List[Folder],
  ): Future[Unit] = {
    val actualToRemove =
      toRemove.filterNot(folder => toAdd.exists(_.path == folder.path))

    def shouldBeRemoved(folder: Folder) =
      actualToRemove.exists(_.path == folder.path)

    val WorkspaceFoldersServices(prev, _, _) =
      folderServices.getAndUpdate {
        case WorkspaceFoldersServices(
              services,
              delegating,
              nonScalaProjects,
            ) =>
          val filteredServices = services.filterNot(shouldBeRemoved)
          val filteredDelegating = delegating.filterNot(shouldBeRemoved)

          val (newScala, newNonScala) = toAdd
            .filterNot(isIn(services ++ delegating ++ nonScalaProjects, _))
            .partition(_.isMetalsProject)

          val allNewScala = newScala.map { folder =>
            findDelegate(filteredServices, folder) match {
              case Some(service) =>
                DelegatingFolderService(folder, service)
              case None => createService(folder)
            }
          }

          val updatedServices = {
            val transformedDelegating =
              filteredDelegating.collect {
                case del if shouldBeRemoved(del.service) => createService(del)
              }

            filteredServices ++ allNewScala.collect {
              case service: MetalsLspService =>
                service
            } ++ transformedDelegating
          }

          val updatedDelegating =
            filteredDelegating.filterNot(del =>
              shouldBeRemoved(del.service)
            ) ++ allNewScala.collect { case service: DelegatingFolderService =>
              service
            }

          val updatedNonScala =
            nonScalaProjects.filterNot(shouldBeRemoved) ++ newNonScala

          WorkspaceFoldersServices(
            updatedServices,
            updatedDelegating,
            updatedNonScala,
          )
      }

    if (getFolderServices.isEmpty) {
      shutdownMetals()
    } else {
      setupLogger()

      val services = getFolderServices.filterNot(isIn(prev, _))
      for {
        _ <- userConfigSync.initSyncUserConfiguration(services)
        _ <- Future.sequence(services.map(_.initialized()))
        _ <- Future(prev.filter(shouldBeRemoved).foreach(_.onShutdown()))
      } yield ()
    }
  }

  def convertToScalaProject(folder: Folder): Option[MetalsLspService] = {
    val WorkspaceFoldersServices(after, delegating, _) =
      folderServices.updateAndGet {
        case wfs @ WorkspaceFoldersServices(
              services,
              delegating,
              nonScalaProjects,
            ) =>
          if (!isIn(services, folder)) {
            findDelegate(services, folder) match {
              case Some(service) =>
                WorkspaceFoldersServices(
                  services,
                  delegating :+ DelegatingFolderService(folder, service),
                  nonScalaProjects.filterNot(_ == folder),
                )
              case None =>
                WorkspaceFoldersServices(
                  services :+ createService(folder),
                  delegating,
                  nonScalaProjects.filterNot(_ == folder),
                )
            }
          } else wfs
      }

    after.find(_.path == folder.path) match {
      case Some(service) =>
        setupLogger()
        userConfigSync
          .initSyncUserConfiguration(List(service))
          .map(_ => service.initialized())
        Some(service)
      case None =>
        delegating.collectFirst {
          case del if del.path == folder.path => del.service
        }
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

  private def findDelegate(
      services: List[MetalsLspService],
      folder: Folder,
  ): Option[MetalsLspService] = {
    val uriString = folder.path.toURI.toString
    services.find(
      _.buildTargets.all.exists(_.baseDirectory == uriString)
    )
  }
}

case class WorkspaceFoldersServices(
    services: List[MetalsLspService],
    delegatingServices: List[DelegatingFolderService],
    nonScalaFolders: List[Folder],
)
