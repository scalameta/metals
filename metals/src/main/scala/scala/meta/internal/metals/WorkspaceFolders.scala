package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.logging.MetalsLogger

class WorkspaceFolders(
    initialFolders: List[Folder],
    createService: Folder => ProjectMetalsLspService,
    redirectSystemOut: Boolean,
    initialServerConfig: MetalsServerConfig,
    userConfigSync: UserConfigurationSync,
)(implicit ec: ExecutionContext) {

  private val allFolders: AtomicReference[List[Folder]] =
    new AtomicReference(initialFolders)
  private val folderServices: AtomicReference[WorkspaceFoldersServices] =
    new AtomicReference(initServices(initialFolders))

  def getFolderServices: List[ProjectMetalsLspService] =
    folderServices.get().services
  def nonScalaProjects: List[Folder] = folderServices.get().nonScalaFolders

  def initServices(folders: List[Folder]): WorkspaceFoldersServices = {
    val (scalaProjects, nonScalaProjects) =
      folders.partition(_.isMetalsProject)
    val knowProjectRefs = scalaProjects.flatMap(_.projectReferences).toSet
    if (knowProjectRefs.nonEmpty)
      scribe.debug("Found project references: " + knowProjectRefs)
    if (scalaProjects.nonEmpty)
      scribe.debug("Found Scala projects: " + scalaProjects.map(_.path))
    val scalaServices =
      scalaProjects
        .filterNot(path =>
          path.optDelegatePath.exists(path =>
            scalaProjects.exists(_.path == path)
          ) ||
            knowProjectRefs(path.path)
        )
        .map(createService)
    WorkspaceFoldersServices(scalaServices, nonScalaProjects)
  }

  def changeFolderServices(
      toRemove: List[Folder],
      toAdd: List[Folder],
  ): Future[Unit] = {
    val actualToRemove =
      toRemove.filterNot(folder => toAdd.exists(_.path == folder.path))

    def shouldBeRemoved(folder: Folder) =
      actualToRemove.exists(_.path == folder.path)

    allFolders.updateAndGet(_.filterNot(shouldBeRemoved) ++ toAdd)

    val knowProjectRoots = toAdd.flatMap(_.projectReferences)
    val actualToAdd = toAdd.filterNot { folder =>
      findDelegate(getFolderServices.filterNot(shouldBeRemoved), folder) match {
        case Some(_) => true
        case _ =>
          folder.optDelegatePath.exists(path => toAdd.exists(_.path == path)) &&
          !knowProjectRoots.contains(folder.path)
      }
    }

    val servicesToInit =
      if (actualToRemove.isEmpty) {
        val WorkspaceFoldersServices(prev, _) =
          folderServices.getAndUpdate {
            case WorkspaceFoldersServices(
                  services,
                  nonScalaProjects,
                ) =>
              val (newScala, newNonScala) = actualToAdd
                .filterNot(isIn(services ++ nonScalaProjects, _))
                .partition(_.isMetalsProject)

              val allNewScala = newScala.map(createService)

              WorkspaceFoldersServices(
                services ++ allNewScala,
                nonScalaProjects ++ newNonScala,
              )
          }
        getFolderServices.filterNot(isIn(prev, _))
      } else {
        val WorkspaceFoldersServices(prev, _) =
          folderServices.getAndUpdate(_ => initServices(allFolders.get()))

        prev.foreach(_.onShutdown())
        getFolderServices
      }

    setupLogger()
    for {
      _ <- userConfigSync.initSyncUserConfiguration(servicesToInit)
      _ <- Future.sequence(servicesToInit.map(_.initialized()))
    } yield ()
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
  ): Option[MetalsLspService] = {
    val uri = folder.path.toURI
    services.find(
      _.buildTargets.all
        .exists(_.baseDirectoryPath.map(_.toURI).contains(uri))
    )
  }
}

case class WorkspaceFoldersServices(
    services: List[ProjectMetalsLspService],
    nonScalaFolders: List[Folder],
)
