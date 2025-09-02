package scala.meta.internal.metals

import java.nio.file.Path
import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.{Map => MMap}
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Properties

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.JavacOptionsResult
import ch.epfl.scala.bsp4j.JvmCompileClasspathParams
import ch.epfl.scala.bsp4j.MavenDependencyModule
import ch.epfl.scala.bsp4j.MavenDependencyModuleArtifact
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import ch.epfl.scala.bsp4j.SourceItem
import ch.epfl.scala.bsp4j.SourceItemKind.DIRECTORY
import ch.epfl.scala.bsp4j.SourceItemKind.FILE
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import org.eclipse.{lsp4j => l}

final class TargetData() {

  val sourceItemsToBuildTarget
      : MMap[AbsolutePath, ConcurrentLinkedQueue[BuildTargetIdentifier]] =
    TrieMap.empty[AbsolutePath, ConcurrentLinkedQueue[BuildTargetIdentifier]]
  val buildTargetInfo: MMap[BuildTargetIdentifier, BuildTarget] =
    TrieMap.empty[BuildTargetIdentifier, BuildTarget]
  val javaTargetInfo: MMap[BuildTargetIdentifier, JavaTarget] =
    TrieMap.empty[BuildTargetIdentifier, JavaTarget]
  val scalaTargetInfo: MMap[BuildTargetIdentifier, ScalaTarget] =
    TrieMap.empty[BuildTargetIdentifier, ScalaTarget]
  val inverseDependencies
      : MMap[BuildTargetIdentifier, ListBuffer[BuildTargetIdentifier]] =
    TrieMap.empty[BuildTargetIdentifier, ListBuffer[BuildTargetIdentifier]]
  val buildTargetSources: MMap[BuildTargetIdentifier, util.Set[AbsolutePath]] =
    TrieMap.empty[BuildTargetIdentifier, util.Set[AbsolutePath]]
  val buildTargetClasspath: MMap[BuildTargetIdentifier, List[String]] =
    TrieMap.empty[BuildTargetIdentifier, List[String]]
  val buildTargetDependencyModules
      : MMap[BuildTargetIdentifier, List[MavenDependencyModule]] =
    TrieMap.empty[BuildTargetIdentifier, List[MavenDependencyModule]]
  val inverseDependencySources: MMap[AbsolutePath, Set[BuildTargetIdentifier]] =
    TrieMap.empty[AbsolutePath, Set[BuildTargetIdentifier]]
  val buildTargetGeneratedDirs: MMap[AbsolutePath, Unit] =
    TrieMap.empty[AbsolutePath, Unit]
  val buildTargetGeneratedFiles: MMap[AbsolutePath, Unit] =
    TrieMap.empty[AbsolutePath, Unit]
  val sourceJarNameToJarFile: MMap[String, AbsolutePath] =
    TrieMap.empty[String, AbsolutePath]
  val isSourceRoot: util.Set[AbsolutePath] =
    ConcurrentHashSet.empty[AbsolutePath]
  // if workspace contains symlinks, original source items are kept here and source items dealiased
  val originalSourceItems: util.Set[AbsolutePath] =
    ConcurrentHashSet.empty[AbsolutePath]
  val sourceItemFiles: util.Set[AbsolutePath] =
    ConcurrentHashSet.empty[AbsolutePath]

  val targetToConnection: MMap[BuildTargetIdentifier, BuildServerConnection] =
    new mutable.HashMap[BuildTargetIdentifier, BuildServerConnection]
  def sourceBuildTargets(
      sourceItem: AbsolutePath
  ): Option[Iterable[BuildTargetIdentifier]] = {
    val valueOrNull = sourceBuildTargetsCache.get(sourceItem)
    if (valueOrNull == null || valueOrNull.isEmpty) {
      val sourceItemNIO = sourceItem.toNIO
      val value = sourceItemsToBuildTarget.map{case (path, buildTargets) => path.toNIO -> buildTargets}.collectFirst {
        case (source, buildTargets)
            if sourceItemNIO.getFileSystem == source.getFileSystem &&
              sourceItemNIO.startsWith(source) =>
          buildTargets.asScala
      }
      val prevOrNull = sourceBuildTargetsCache.put(sourceItem, value)
      if (prevOrNull == null) value
      else prevOrNull
    } else valueOrNull
  }

  def allTargetRoots: Iterator[AbsolutePath] = {
    val scalaTargetRoots = scalaTargetInfo.map(_._2.targetroot)
    val javaTargetRoots = javaTargetInfo.flatMap(_._2.targetroot)
    val allTargetRoots = scalaTargetRoots.toSet ++ javaTargetRoots.toSet
    allTargetRoots.iterator
  }
  def all: Iterator[BuildTarget] =
    buildTargetInfo.values.toIterator

  def allBuildTargetIds: Seq[BuildTargetIdentifier] =
    buildTargetInfo.keys.toSeq
  def allScala: Iterator[ScalaTarget] =
    scalaTargetInfo.values.toIterator
  def allJava: Iterator[JavaTarget] =
    javaTargetInfo.values.toIterator
  def scalaTarget(id: BuildTargetIdentifier): Option[ScalaTarget] =
    scalaTargetInfo.get(id)
  def javaTarget(id: BuildTargetIdentifier): Option[JavaTarget] =
    javaTargetInfo.get(id)
  def jvmTarget(id: BuildTargetIdentifier): Option[JvmTarget] =
    scalaTarget(id).orElse(javaTarget(id))
  def jvmTargets(id: BuildTargetIdentifier): List[JvmTarget] =
    List(scalaTarget(id), javaTarget(id)).flatten

  private val sourceBuildTargetsCache =
    new util.concurrent.ConcurrentHashMap[AbsolutePath, Option[
      Iterable[BuildTargetIdentifier]
    ]]

  val actualSources: MMap[AbsolutePath, TargetData.MappedSource] =
    TrieMap.empty[AbsolutePath, TargetData.MappedSource]

  def targetRoots(buildTarget: BuildTargetIdentifier): List[AbsolutePath] = {
    val javaRoot = javaTargetRoot(buildTarget).toList
    val scalaRoot = scalaTargetRoot(buildTarget).toList
    (javaRoot ++ scalaRoot).distinct
  }

  def javaTargetRoot(buildTarget: BuildTargetIdentifier): Option[AbsolutePath] =
    javaTarget(buildTarget).flatMap(_.targetroot)

  def scalaTargetRoot(
      buildTarget: BuildTargetIdentifier
  ): Option[AbsolutePath] =
    scalaTarget(buildTarget).map(_.targetroot)

  def info(id: BuildTargetIdentifier): Option[BuildTarget] =
    buildTargetInfo.get(id)

  /**
   * Get jars for a specific build target.
   *
   * We first try to use buildTargetDependencyModules
   * request since it should be low cost for build tools
   * like Bazel.
   *
   * We fall back to reading from classpath only if the
   * classpath is read eagerly.
   *
   * @param id id of the queried target
   * @return depenendency jar list if available
   */
  def targetJarClasspath(
      id: BuildTargetIdentifier
  ): Option[List[AbsolutePath]] = {
    val fromDepModules =
      for {
        module <- buildTargetDependencyModules.getOrElse(id, Nil)
        artifact <- module.getArtifacts().asScala
        path <- artifact match {
          case artifact: MavenDependencyModuleArtifact
              if artifact.getClassifier() == null =>
            Some(artifact.getUri().toAbsolutePath)
          case _ => None
        }
      } yield path

    if (fromDepModules.isEmpty)
      jvmTargets(id).flatMap(_.jarClasspath).headOption
    else Some(fromDepModules)
  }

  def targetClasspath(
      id: BuildTargetIdentifier,
      cancelPromise: Promise[Unit],
  )(implicit ec: ExecutionContext): Option[Future[List[String]]] = {
    targetToConnection.get(id).zip(jvmTarget(id)).map {
      case (bspConnection, jvmTarget) =>
        val classpath =
          jvmTarget.classpath.orElse(buildTargetClasspath.get(id)) match {
            case None =>
              bspConnection
                .buildTargetJvmClasspath(
                  new JvmCompileClasspathParams(List(id).asJava),
                  cancelPromise,
                )
                .map { classpathResult =>
                  val classpath = classpathResult
                    .getItems()
                    .asScala
                    .map(_.getClasspath().asScala)
                    .flatten
                    .toList
                  buildTargetClasspath.put(id, classpath)
                  classpath
                }
            case Some(classpath) => Future.successful(classpath)
          }

        classpath.map { classes =>
          val outputClasses = jvmTarget.classDirectory
          if (classes.contains(outputClasses)) classes
          else outputClasses :: classes
        }

    }
  }

  def findConnectedArtifact(
      jar: AbsolutePath,
      targetId: Option[BuildTargetIdentifier],
      classifier: String = "sources",
  ): Option[AbsolutePath] = {
    val jarUri = jar.toURI.toString()
    def depModules: Iterator[MavenDependencyModule] = targetId match {
      case None => buildTargetDependencyModules.values.flatten.iterator
      case Some(id) => buildTargetDependencyModules.get(id).iterator.flatten
    }

    /**
     * For windows file:///C:/Users/runneradmin/AppData/Local/Coursier/Cache and
     * file:///C:/Users/runneradmin/AppData/Local/Coursier/cache is equivalent
     */
    def isUriEqual(uri: String, otherUri: String) = {
      Properties.isWin && uri.toLowerCase() == otherUri
        .toLowerCase() || uri == otherUri
    }
    val allFound = for {
      module <- depModules
      artifacts = module.getArtifacts().asScala
      if artifacts.exists(artifact => isUriEqual(artifact.getUri(), jarUri))
      foundJar <- artifacts.find(_.getClassifier() == classifier)
      foundJarPath = foundJar.getUri().toAbsolutePath
      if foundJarPath.exists
    } yield foundJarPath
    allFound.headOption
  }

  def targetClassDirectories(id: BuildTargetIdentifier): List[String] = {
    val scalacData =
      scalaTarget(id).map(_.scalac.getClassDirectory).filter(_.nonEmpty).toList
    val javacData =
      javaTarget(id).map(_.javac.getClassDirectory).filter(_.nonEmpty).toList
    (scalacData ++ javacData).distinct
  }

  def allWorkspaceJars: Iterator[AbsolutePath] = {
    val isVisited = new ju.HashSet[AbsolutePath]()

    Iterator(
      for {
        targetId <- allBuildTargetIds
        classpathEntries <- targetJarClasspath(targetId).toList
        classpathEntry <- classpathEntries
        if isVisited.add(classpathEntry)
      } yield classpathEntry,
      PackageIndex.bootClasspath.map(AbsolutePath.apply).iterator,
    ).flatten
  }

  def addSourceItem(
      sourceItem: AbsolutePath,
      buildTarget: BuildTargetIdentifier,
  ): Unit = {
    val dealiased = sourceItem.dealias
    if (dealiased != sourceItem)
      originalSourceItems.add(sourceItem)

    val queue = sourceItemsToBuildTarget.getOrElseUpdate(
      dealiased,
      new ConcurrentLinkedQueue(),
    )
    queue.add(buildTarget)
    sourceBuildTargetsCache.clear()
  }

  def addSourceItem(
      sourceItem: SourceItem,
      buildTarget: BuildTargetIdentifier,
  ): Unit = {
    val sourceItemPath = sourceItem.getUri.toAbsolutePath(followSymlink = false)

    sourceItem.getKind match {
      case DIRECTORY =>
        if (sourceItem.getGenerated)
          buildTargetGeneratedDirs(sourceItemPath) = ()
      case FILE =>
        if (sourceItem.getGenerated)
          buildTargetGeneratedFiles(sourceItemPath) = ()
        sourceItemFiles.add(sourceItemPath)
    }
    addSourceItem(sourceItemPath, buildTarget)
  }

  def linkSourceFile(id: BuildTargetIdentifier, source: AbsolutePath): Unit = {
    val set = buildTargetSources.getOrElseUpdate(id, ConcurrentHashSet.empty)
    set.add(source)
  }

  def reset(): Unit = {
    sourceItemsToBuildTarget.values.foreach(_.clear())
    sourceItemsToBuildTarget.clear()
    sourceBuildTargetsCache.clear()
    buildTargetInfo.clear()
    javaTargetInfo.clear()
    scalaTargetInfo.clear()
    inverseDependencies.clear()
    buildTargetSources.clear()
    buildTargetGeneratedDirs.clear()
    buildTargetGeneratedFiles.clear()
    inverseDependencySources.clear()
    sourceJarNameToJarFile.clear()
    isSourceRoot.clear()
  }

  def addWorkspaceBuildTargets(result: WorkspaceBuildTargetsResult): Unit = {
    result.getTargets.asScala.foreach { target =>
      buildTargetInfo(target.getId) = target
      target.getDependencies.asScala.foreach { dependency =>
        val buf =
          inverseDependencies.getOrElseUpdate(dependency, ListBuffer.empty)
        buf += target.getId
      }
    }
  }

  def isSourceFile(source: AbsolutePath): Boolean = {
    sourceItemFiles.contains(source)
  }

  def checkIfGeneratedSource(source: Path): Boolean = {
    val absolutePath = AbsolutePath(source)
    buildTargetGeneratedFiles.contains(absolutePath) ||
    buildTargetGeneratedDirs.keys.exists(generatedDir =>
      absolutePath.toNIO.startsWith(generatedDir.toNIO)
    )
  }

  def checkIfGeneratedDir(path: AbsolutePath): Boolean =
    buildTargetGeneratedDirs.contains(path)

  def addScalacOptions(
      result: ScalacOptionsResult,
      bspConnectionName: Option[BuildServerConnection],
  ): Unit = {
    result.getItems.asScala.foreach { scalac =>
      info(scalac.getTarget()).foreach { info =>
        info.asScalaBuildTarget.foreach { scalaBuildTarget =>
          val sbtTarget = info.asSbtBuildTarget
          val autoImports = sbtTarget.map(_.getAutoImports.asScala.toSeq)
          scalaTargetInfo(scalac.getTarget) = ScalaTarget(
            info,
            scalaBuildTarget,
            scalac,
            autoImports,
            sbtTarget.map(_.getSbtVersion()),
            bspConnectionName,
          )
        }
      }
    }
  }

  def addJavacOptions(
      result: JavacOptionsResult,
      bspSession: Option[BuildServerConnection],
  ): Unit = {
    result.getItems.asScala.foreach { javac =>
      info(javac.getTarget()).foreach { info =>
        javaTargetInfo(javac.getTarget) = JavaTarget(info, javac, bspSession)
      }
    }
  }

  def addDependencySource(
      sourcesJar: AbsolutePath,
      target: BuildTargetIdentifier,
  ): Unit = {
    sourceJarNameToJarFile(sourcesJar.filename) = sourcesJar
    val acc = inverseDependencySources.getOrElse(sourcesJar, Set.empty)
    inverseDependencySources(sourcesJar) = acc + target
  }

  def addMappedSource(
      path: AbsolutePath,
      mapped: TargetData.MappedSource,
  ): Unit =
    actualSources(path) = mapped

  def addDependencyModules(
      dependencyModules: DependencyModulesResult
  ): Unit = {
    dependencyModules.getItems().asScala.groupBy(_.getTarget()).foreach {
      case (id, items) =>
        val modules = items
          .flatMap(_.getModules().asScala)
          .flatMap(_.asMavenDependencyModule)
        buildTargetDependencyModules.put(id, modules.toList)
    }
  }

  def resetConnections(
      idToConn: List[(BuildTargetIdentifier, BuildServerConnection)]
  ): Unit = {
    targetToConnection.clear()
    idToConn.foreach { case (id, conn) => targetToConnection.put(id, conn) }
  }

  def onCreate(source: AbsolutePath): Unit = {
    for {
      buildTargets <- sourceBuildTargets(source)
      buildTarget <- buildTargets
    } {
      linkSourceFile(buildTarget, source)
    }
  }
}

object TargetData {

  trait MappedSource {
    def path: AbsolutePath
    def lineForServer(line: Int): Option[Int] = None
    def lineForClient(line: Int): Option[Int] = None
    def update(
        content: String
    ): (Input.VirtualFile, l.Position => l.Position, AdjustLspData)
  }

}
