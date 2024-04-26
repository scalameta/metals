package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
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
    val scalaServices =
      scalaProjects
        .filterNot(
          _.optDelegatePath.exists(path => scalaProjects.exists(_.path == path))
        )
        .map(createService)
    new AtomicReference(
      WorkspaceFoldersServices(scalaServices, nonScalaProjects)
    )
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

    val actualToAdd = toAdd.filterNot { folder =>
      findDelegate(getFolderServices, folder) match {
        case Some(service) =>
          DelegateSetting.writeDeleteSetting(folder.path, service.path)
          true
        case _ =>
          folder.optDelegatePath.exists(path => toAdd.exists(_.path == path))
      }
    }

    val WorkspaceFoldersServices(prev, _) =
      folderServices.getAndUpdate {
        case WorkspaceFoldersServices(
              services,
              nonScalaProjects,
            ) =>
          val filteredServices = services.filterNot(shouldBeRemoved)

          val (newScala, newNonScala) = actualToAdd
            .filterNot(isIn(services ++ nonScalaProjects, _))
            .partition(_.isMetalsProject)

          val allNewScala = newScala.map(createService)

          val updatedServices = filteredServices ++ allNewScala

          val updatedNonScala =
            nonScalaProjects.filterNot(shouldBeRemoved) ++ newNonScala

          WorkspaceFoldersServices(
            updatedServices,
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
    val WorkspaceFoldersServices(after, _) =
      folderServices.updateAndGet {
        case wfs @ WorkspaceFoldersServices(
              services,
              nonScalaProjects,
            ) =>
          if (!isIn(services, folder)) {
            WorkspaceFoldersServices(
              services :+ createService(folder),
              nonScalaProjects.filterNot(_ == folder),
            )
          } else wfs
      }

    after.find(_.path == folder.path).map { service =>
      setupLogger()
      userConfigSync
        .initSyncUserConfiguration(List(service))
        .map(_ => service.initialized())
      service
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
  ): Option[MetalsLspService] =
    folder.optDelegatePath
      .flatMap(delegate => services.find(_.path == delegate))
      .orElse {
        val uriString = folder.path.toURI.toString
        services.find(
          _.buildTargets.all.exists(_.baseDirectory == uriString)
        )
      }
}

case class WorkspaceFoldersServices(
    services: List[MetalsLspService],
    nonScalaFolders: List[Folder],
)
