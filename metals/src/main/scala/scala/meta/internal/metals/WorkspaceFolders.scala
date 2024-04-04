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

    val allBuildTargetsBases = getFolderServices
      .filterNot(shouldBeRemoved)
      .flatMap(service => service.buildTargets.all.map((_, service)))
      .map { case (bt, service) => (bt.baseDirectory, service) }

    val (newScala, newNonScala) = toAdd.partition(_.isMetalsProject)

    val allNewScala = newScala.map { folder =>
      val pathString = folder.path.toURI.toString
      allBuildTargetsBases.collectFirst {
        case (base, service) if base == pathString => service
      } match {
        case Some(service) =>
          new DelegatingFolderService(folder.path, folder.visibleName, service)
        case None => createService(folder)
      }
    }

    val newServices = {
      val transformedDelegating =
        delegatingServices.filterNot(shouldBeRemoved).collect {
          case del if shouldBeRemoved(del.service) => createService(del)
        }

      allNewScala.collect { case service: MetalsLspService =>
        service
      } ++ transformedDelegating
    }

    val newDelegatingServices = allNewScala.collect {
      case service: DelegatingFolderService => service
    }

    if (newServices.isEmpty && getFolderServices.forall(shouldBeRemoved)) {
      shutdownMetals()
    } else {
      val WorkspaceFoldersServices(prev, _, _) =
        folderServices.getAndUpdate {
          case WorkspaceFoldersServices(
                services,
                delegating,
                nonScalaProjects,
              ) =>
            val updatedServices =
              services.filterNot(shouldBeRemoved) ++
                newServices.filterNot(isIn(services ++ delegating, _))
            val updatedNonScala =
              nonScalaProjects.filterNot(shouldBeRemoved) ++
                newNonScala.filterNot(
                  isIn(nonScalaProjects ++ services ++ delegating, _)
                )
            val updatedDelegating =
              delegating.filterNot(del =>
                shouldBeRemoved(del) || shouldBeRemoved(del.service)
              ) ++
                newDelegatingServices.filterNot(isIn(services ++ delegating, _))

            // `transformedDelegating` is not thread safe but it should be okay to spill this to `non-scala` projects
            val (safeUpdatedDelegating, other) =
              updatedDelegating.partition(del =>
                updatedServices.contains(del.service)
              )

            WorkspaceFoldersServices(
              updatedServices,
              safeUpdatedDelegating,
              updatedNonScala ++ other,
            )
        }

      setupLogger()

      val services = newServices.filterNot(isIn(prev, _))
      for {
        _ <- userConfigSync.initSyncUserConfiguration(services)
        _ <- Future.sequence(services.map(_.initialized()))
        _ <- Future(prev.filter(shouldBeRemoved).foreach(_.onShutdown()))
      } yield ()
    }
  }

  def convertToScalaProject(folder: Folder): MetalsLspService = {
    val newService = createService(folder)
    val WorkspaceFoldersServices(prev, _, _) = folderServices.getAndUpdate {
      case wfs @ WorkspaceFoldersServices(
            services,
            delegating,
            nonScalaProjects,
          ) =>
        if (!isIn(services, folder)) {
          WorkspaceFoldersServices(
            services :+ newService,
            delegating,
            nonScalaProjects.filterNot(_ == folder),
          )
        } else wfs
    }

    prev.find(_.path == folder.path) match {
      case Some(service) => service
      case None =>
        setupLogger()
        userConfigSync
          .initSyncUserConfiguration(List(newService))
          .map(_ => newService.initialized())
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
    delegatingServices: List[DelegatingFolderService],
    nonScalaFolders: List[Folder],
)
