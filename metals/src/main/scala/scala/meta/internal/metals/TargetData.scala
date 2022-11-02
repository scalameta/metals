package scala.meta.internal.metals

import java.nio.file.Path
import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.{Map => MMap}

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JavacOptionsResult
import ch.epfl.scala.bsp4j.JvmEnvironmentItem
import ch.epfl.scala.bsp4j.JvmRunEnvironmentResult
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import ch.epfl.scala.bsp4j.SourceItem
import ch.epfl.scala.bsp4j.SourceItemKind.DIRECTORY
import ch.epfl.scala.bsp4j.SourceItemKind.FILE
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import org.eclipse.{lsp4j => l}

final class TargetData {

  val sourceItemsToBuildTarget
      : MMap[AbsolutePath, ConcurrentLinkedQueue[BuildTargetIdentifier]] =
    TrieMap.empty[AbsolutePath, ConcurrentLinkedQueue[BuildTargetIdentifier]]
  val buildTargetInfo: MMap[BuildTargetIdentifier, BuildTarget] =
    TrieMap.empty[BuildTargetIdentifier, BuildTarget]
  val javaTargetInfo: MMap[BuildTargetIdentifier, JavaTarget] =
    TrieMap.empty[BuildTargetIdentifier, JavaTarget]
  val scalaTargetInfo: MMap[BuildTargetIdentifier, ScalaTarget] =
    TrieMap.empty[BuildTargetIdentifier, ScalaTarget]
  val jvmRunEnvironments: MMap[BuildTargetIdentifier, JvmEnvironmentItem] =
    TrieMap.empty[BuildTargetIdentifier, JvmEnvironmentItem]
  val inverseDependencies
      : MMap[BuildTargetIdentifier, ListBuffer[BuildTargetIdentifier]] =
    TrieMap.empty[BuildTargetIdentifier, ListBuffer[BuildTargetIdentifier]]
  val buildTargetSources: MMap[BuildTargetIdentifier, util.Set[AbsolutePath]] =
    TrieMap.empty[BuildTargetIdentifier, util.Set[AbsolutePath]]
  val inverseDependencySources: MMap[AbsolutePath, Set[BuildTargetIdentifier]] =
    TrieMap.empty[AbsolutePath, Set[BuildTargetIdentifier]]
  val buildTargetGeneratedDirs: MMap[AbsolutePath, Unit] =
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
    if (valueOrNull == null) {
      val value = sourceItemsToBuildTarget.collectFirst {
        case (source, buildTargets)
            if sourceItem.toNIO.getFileSystem == source.toNIO.getFileSystem &&
              sourceItem.toNIO.startsWith(source.toNIO) =>
          buildTargets.asScala
      }
      val prevOrNull = sourceBuildTargetsCache.putIfAbsent(sourceItem, value)
      if (prevOrNull == null) value
      else prevOrNull
    } else valueOrNull
  }

  def allTargetRoots: Iterator[AbsolutePath] = {
    val scalaTargetRoots = scalaTargetInfo.map(_._2.targetroot)
    val javaTargetRoots = javaTargetInfo.map(_._2.targetroot)
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
    javaTarget(buildTarget).map(_.targetroot)

  def scalaTargetRoot(
      buildTarget: BuildTargetIdentifier
  ): Option[AbsolutePath] =
    scalaTarget(buildTarget).map(_.targetroot)

  def info(id: BuildTargetIdentifier): Option[BuildTarget] =
    buildTargetInfo.get(id)

  def targetJarClasspath(
      id: BuildTargetIdentifier
  ): Option[List[AbsolutePath]] = {
    val scalacData = scalaTarget(id).map(_.scalac.jarClasspath)
    val javacData = javaTarget(id).map(_.javac.jarClasspath)
    scalacData
      .flatMap(s => javacData.map(j => (s ::: j).distinct).orElse(scalacData))
      .orElse(javacData)
  }

  def targetClasspath(
      id: BuildTargetIdentifier
  ): Option[List[String]] = {
    val scalacData = scalaTarget(id).map(_.scalac.classpath)
    val javacData = javaTarget(id).map(_.javac.classpath)
    scalacData
      .flatMap(s => javacData.map(j => (s ::: j).distinct).orElse(scalacData))
      .orElse(javacData)
  }

  def targetClassDirectories(id: BuildTargetIdentifier): List[String] = {
    val scalacData = scalaTarget(id).map(_.scalac.getClassDirectory).toList
    val javacData = javaTarget(id).map(_.javac.getClassDirectory).toList
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
      PackageIndex.bootClasspath.iterator,
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

    sourceItem.getKind() match {
      case DIRECTORY => {
        if (sourceItem.getGenerated()) {
          buildTargetGeneratedDirs(sourceItemPath) = ()
        }
      }
      case FILE => {
        sourceItemFiles.add(sourceItemPath)
      }
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
    buildTargetGeneratedDirs.keys.exists(generatedDir =>
      source.startsWith(generatedDir.toNIO)
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

  def addJvmEnvironment(
      result: JvmRunEnvironmentResult
  ): Unit = {
    result.getItems.asScala.foreach { env =>
      jvmRunEnvironments(env.getTarget()) = env
    }
  }

  def addJavacOptions(result: JavacOptionsResult): Unit = {
    result.getItems.asScala.foreach { javac =>
      info(javac.getTarget()).foreach { info =>
        javaTargetInfo(javac.getTarget) = JavaTarget(info, javac)
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
