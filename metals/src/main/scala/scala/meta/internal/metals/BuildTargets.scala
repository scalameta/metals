package scala.meta.internal.metals

import java.lang.{Iterable => JIterable}
import java.net.URLClassLoader
import java.nio.file.Path
import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JavacOptionsResult
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import ch.epfl.scala.bsp4j.SourceItem
import ch.epfl.scala.bsp4j.SourceItemKind
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult

/**
 * In-memory cache for looking up build server metadata.
 */
final class BuildTargets(
    ammoniteBuildServer: BuildTargetIdentifier => Option[BuildServerConnection]
) {
  private var workspace = PathIO.workingDirectory
  def setWorkspaceDirectory(newWorkspace: AbsolutePath): Unit = {
    workspace = newWorkspace
  }
  private var tables: Option[Tables] = None
  private val sourceItemsToBuildTarget =
    TrieMap.empty[AbsolutePath, ConcurrentLinkedQueue[BuildTargetIdentifier]]
  private val buildTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, BuildTarget]
  private val javaTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, JavaTarget]
  private val scalaTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, ScalaTarget]
  private val inverseDependencies =
    TrieMap.empty[BuildTargetIdentifier, ListBuffer[BuildTargetIdentifier]]
  private val buildTargetSources =
    TrieMap.empty[BuildTargetIdentifier, util.Set[AbsolutePath]]
  private val inverseDependencySources =
    TrieMap.empty[AbsolutePath, Set[BuildTargetIdentifier]]
  private val buildTargetGeneratedDirs: TrieMap[AbsolutePath, Unit] =
    TrieMap.empty[AbsolutePath, Unit]
  private val sourceJarNameToJarFile = TrieMap.empty[String, AbsolutePath]
  private val isSourceRoot =
    ConcurrentHashSet.empty[AbsolutePath]
  // if workspace contains symlinks, original source items are kept here and source items dealiased
  private val originalSourceItems = ConcurrentHashSet.empty[AbsolutePath]

  private val targetToConnection =
    new mutable.HashMap[BuildTargetIdentifier, BuildServerConnection]

  val buildTargetsOrder: BuildTargetIdentifier => Int = {
    (t: BuildTargetIdentifier) =>
      var score = 1

      val isSupportedScalaVersion = scalaTarget(t).exists(t =>
        ScalaVersions.isSupportedAtReleaseMomentScalaVersion(
          t.scalaVersion
        )
      )
      if (isSupportedScalaVersion) score <<= 2

      val usesJavac = javaTarget(t).nonEmpty
      val isJVM = scalaTarget(t).exists(_.scalac.isJVM)
      if (usesJavac) score <<= 1
      else if (isJVM) score <<= 1

      // note(@tgodzik) once the support for Scala 3 is on par with Scala 2 this can be removed
      val isScala2 = scalaTarget(t).exists(info =>
        !ScalaVersions.isScala3Version(info.scalaVersion)
      )
      if (isScala2) score <<= 1

      score
  }

  def setTables(newTables: Tables): Unit = {
    tables = Some(newTables)
  }

  def reset(): Unit = {
    sourceItemsToBuildTarget.values.foreach(_.clear())
    sourceItemsToBuildTarget.clear()
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
  def sourceItems: Iterable[AbsolutePath] = sourceItemsToBuildTarget.keys
  def sourceItemsToBuildTargets
      : Iterator[(AbsolutePath, JIterable[BuildTargetIdentifier])] =
    sourceItemsToBuildTarget.iterator

  def allBuildTargetIds: Seq[BuildTargetIdentifier] = buildTargetInfo.keys.toSeq

  def allTargetRoots: Iterator[AbsolutePath] = {
    val scalaTargetRoots = scalaTargetInfo.map(_._2.targetroot)
    val javaTargetRoots = javaTargetInfo.map(_._2.targetroot)
    val allTargetRoots = scalaTargetRoots.toSet ++ javaTargetRoots.toSet
    allTargetRoots.iterator
  }

  def all: Iterator[BuildTarget] =
    buildTargetInfo.values.toIterator

  def allScala: Iterator[ScalaTarget] =
    scalaTargetInfo.values.toIterator

  def allJava: Iterator[JavaTarget] =
    javaTargetInfo.values.toIterator

  def info(id: BuildTargetIdentifier): Option[BuildTarget] =
    buildTargetInfo.get(id)

  def scalaTarget(id: BuildTargetIdentifier): Option[ScalaTarget] =
    scalaTargetInfo.get(id)

  def javaTarget(id: BuildTargetIdentifier): Option[JavaTarget] =
    javaTargetInfo.get(id)

  def targetJarClasspath(
      id: BuildTargetIdentifier
  ): Option[List[AbsolutePath]] = {
    val scalacData = scalaTarget(id).map(_.scalac.jarClasspath)
    val javacData = javaTarget(id).map(_.javac.jarClasspath)
    scalacData
      .flatMap(s => javacData.map(j => (s ::: j).distinct).orElse(scalacData))
      .orElse(javacData)
  }

  private def targetClasspath(
      id: BuildTargetIdentifier
  ): Option[List[String]] = {
    val scalacData = scalaTarget(id).map(_.scalac.classpath)
    val javacData = javaTarget(id).map(_.javac.classpath)
    scalacData
      .flatMap(s => javacData.map(j => (s ::: j).distinct).orElse(scalacData))
      .orElse(javacData)
  }

  def targetClassDirectories(
      id: BuildTargetIdentifier
  ): List[String] = {
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
      PackageIndex.bootClasspath.iterator
    ).flatten
  }

  def allSourceJars: Iterator[AbsolutePath] =
    inverseDependencySources.keysIterator

  def addSourceItem(
      sourceItem: AbsolutePath,
      buildTarget: BuildTargetIdentifier
  ): Unit = {
    val dealiased = sourceItem.dealias
    if (dealiased != sourceItem)
      originalSourceItems.add(sourceItem)

    val queue = sourceItemsToBuildTarget.getOrElseUpdate(
      dealiased,
      new ConcurrentLinkedQueue()
    )
    queue.add(buildTarget)
  }

  def addSourceItem(
      sourceItem: SourceItem,
      buildTarget: BuildTargetIdentifier
  ): Unit = {
    val sourceItemPath = sourceItem.getUri.toAbsolutePath(followSymlink = false)
    if (
      sourceItem.getKind() == SourceItemKind.DIRECTORY &&
      sourceItem.getGenerated()
    ) {
      buildTargetGeneratedDirs(sourceItemPath) = ()
    }
    addSourceItem(sourceItemPath, buildTarget)
  }

  def onCreate(source: AbsolutePath): Unit = {
    for {
      buildTarget <- sourceBuildTargets(source)
    } {
      linkSourceFile(buildTarget, source)
    }
  }

  def buildTargetSources(
      id: BuildTargetIdentifier
  ): Iterable[AbsolutePath] = {
    this.buildTargetSources.get(id) match {
      case None => Nil
      case Some(value) => value.asScala
    }
  }

  def buildTargetTransitiveSources(
      id: BuildTargetIdentifier
  ): Iterator[AbsolutePath] = {
    for {
      dependency <- buildTargetTransitiveDependencies(id).iterator
      sources <- buildTargetSources.get(dependency).iterator
      source <- sources.asScala.iterator
    } yield source
  }

  def buildTargetTransitiveDependencies(
      id: BuildTargetIdentifier
  ): Iterable[BuildTargetIdentifier] = {
    val isVisited = mutable.Set.empty[BuildTargetIdentifier]
    val toVisit = new java.util.ArrayDeque[BuildTargetIdentifier]
    toVisit.add(id)
    while (!toVisit.isEmpty) {
      val next = toVisit.pop()
      if (!isVisited(next)) {
        isVisited.add(next)
        for {
          info <- info(next).iterator
          dependency <- info.getDependencies.asScala.iterator
        } {
          toVisit.add(dependency)
        }
      }
    }
    isVisited
  }

  def linkSourceFile(id: BuildTargetIdentifier, source: AbsolutePath): Unit = {
    val set = buildTargetSources.getOrElseUpdate(id, ConcurrentHashSet.empty)
    set.add(source)
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

  def checkIfGeneratedSource(source: Path): Boolean = {
    buildTargetGeneratedDirs.keys.exists(generatedDir =>
      source.startsWith(generatedDir.toNIO)
    )
  }

  def addScalacOptions(result: ScalacOptionsResult): Unit = {
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
            sbtTarget.map(_.getSbtVersion())
          )
        }
      }
    }
  }

  def addJavacOptions(result: JavacOptionsResult): Unit = {
    result.getItems.asScala.foreach { javac =>
      info(javac.getTarget()).foreach { info =>
        javaTargetInfo(javac.getTarget) = JavaTarget(info, javac)
      }
    }
  }

  def targetRoots(
      buildTarget: BuildTargetIdentifier
  ): List[AbsolutePath] = {
    val javaRoot = javaTargetRoot(buildTarget).toList
    val scalaRoot = scalaTargetRoot(buildTarget).toList
    (javaRoot ++ scalaRoot).distinct
  }

  def javaTargetRoot(
      buildTarget: BuildTargetIdentifier
  ): Option[AbsolutePath] =
    javaTarget(buildTarget).map(_.targetroot)

  def scalaTargetRoot(
      buildTarget: BuildTargetIdentifier
  ): Option[AbsolutePath] =
    scalaTarget(buildTarget).map(_.targetroot)

  def workspaceDirectory(
      buildTarget: BuildTargetIdentifier
  ): Option[AbsolutePath] =
    buildServerOf(buildTarget).map(_.workspaceDirectory)

  /**
   * Returns the first build target containing this source file.
   */
  def inverseSources(
      source: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    val buildTargets = sourceBuildTargets(source)
    val orSbtBuildTarget =
      if (buildTargets.isEmpty) sbtBuildScalaTarget(source).toIterable
      else buildTargets
    if (orSbtBuildTarget.isEmpty) {
      tables
        .flatMap(_.dependencySources.getBuildTarget(source))
        .orElse(inferBuildTarget(source))
    } else {
      Some(orSbtBuildTarget.maxBy(buildTargetsOrder))
    }
  }

  def scalaVersion(source: AbsolutePath): Option[String] = {
    for {
      id <- inverseSources(source)
      target <- scalaTarget(id)
    } yield target.scalaVersion
  }

  /**
   * Resolves sbt auto imports if a file belongs to a Sbt build target.
   */
  def sbtAutoImports(path: AbsolutePath): Option[Seq[String]] =
    for {
      targetId <- inverseSources(path)
      target <- scalaTarget(targetId)
      imports <- target.autoImports
    } yield imports

  /**
   * Tries to guess what build target this readonly file belongs to from the symbols it defines.
   *
   * By default, we rely on carefully recording what build target produced what
   * files in the `.metals/readonly/` directory. This approach has the problem
   * that navigation failed to work in `readonly/` sources if
   *
   * - a new metals feature forgot to record the build target
   * - a user removes `.metals/metals.h2.db`
   *
   * When encountering an unknown `readonly/` file we do the following steps to
   * infer what build target it belongs to:
   *
   * - check if file is in `.metals/readonly/dependencies/${source-jar-name}`
   * - find the build targets that have a sourceDependency with that name
   *
   * Otherwise if it's a jar file we find a build target it belongs to.
   *
   * This approach is not glamorous but it seems to work reasonably well.
   */
  def inferBuildTarget(
      source: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    if (source.isJarFileSystem) {
      for {
        jarName <- source.jarPath.map(_.filename)
        sourceJarFile <- sourceJarFile(jarName)
        buildTargetId <- inverseDependencySource(sourceJarFile).headOption
      } yield buildTargetId
    } else {
      val readonly = workspace.resolve(Directories.readonly)
      source.toRelativeInside(readonly) match {
        case Some(rel) =>
          val names = rel.toNIO.iterator().asScala.toList.map(_.filename)
          names match {
            case Directories.dependenciesName :: jarName :: _ =>
              // match build target by source jar name
              sourceJarFile(jarName)
                .flatMap(inverseDependencySource(_).headOption)
            case _ => None
          }
        case None =>
          // else it can be a source file inside a jar
          val fromJar = jarPath(source)
            .flatMap { jar =>
              allBuildTargetIds.find { id =>
                targetJarClasspath(id).exists(_.contains(jar))
              }
            }
          fromJar.foreach(addSourceItem(source, _))
          fromJar
      }
    }
  }

  def findByDisplayName(name: String): Option[BuildTarget] = {
    buildTargetInfo.values.find(_.getDisplayName() == name)
  }

  private def jarPath(source: AbsolutePath): Option[AbsolutePath] = {
    source.jarPath.map { sourceJarPath =>
      sourceJarPath.parent.resolve(
        source.filename.replace("-sources.jar", ".jar")
      )
    }
  }

  /**
   * Returns meta build target for `*.sbt` or `*.scala`  files.
   * It selects build target by directory of its connection
   *   because `*.sbt` and `*.scala` aren't included in `sourceFiles` set
   */
  def sbtBuildScalaTarget(
      file: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    val targetMetaBuildDir =
      if (file.isSbt) file.parent.resolve("project") else file.parent
    buildTargetInfo.values
      .find { target =>
        val isMetaBuild = target.isSbtBuild
        if (isMetaBuild) {
          workspaceDirectory(target.getId)
            .map(_ == targetMetaBuildDir)
            .getOrElse(false)
        } else {
          false
        }
      }
      .map(_.getId())
  }

  case class InferredBuildTarget(
      jar: AbsolutePath,
      symbol: String,
      id: BuildTargetIdentifier
  )
  def inferBuildTarget(
      toplevels: Iterable[Symbol]
  ): Option[InferredBuildTarget] = {
    val classloader = new URLClassLoader(
      allWorkspaceJars.map(_.toNIO.toUri().toURL()).toArray,
      null
    )
    lazy val classpaths: Seq[(BuildTargetIdentifier, Seq[AbsolutePath])] =
      allBuildTargetIds.map(id =>
        id -> targetClasspath(id)
          .map(_.toAbsoluteClasspath.toSeq)
          .getOrElse(Seq.empty)
      )

    try {
      toplevels.foldLeft(Option.empty[InferredBuildTarget]) {
        case (Some(x), _) => Some(x)
        case (None, toplevel) =>
          val classfile = toplevel.owner.value + toplevel.displayName + ".class"
          val resource = classloader
            .findResource(classfile)
            .toURI()
            .toString()
            .replaceFirst("!/.*", "")
            .stripPrefix("jar:")
          val path = resource.toAbsolutePath
          classpaths.collectFirst {
            case (id, classpath) if classpath.contains(path) =>
              InferredBuildTarget(path, toplevel.value, id)
          }
      }
    } catch {
      case NonFatal(_) =>
        None
    } finally {
      classloader.close()
    }
  }

  def sourceBuildTargets(
      sourceItem: AbsolutePath
  ): Iterable[BuildTargetIdentifier] = {
    sourceItemsToBuildTarget
      .collectFirst {
        case (source, buildTargets)
            if sourceItem.toNIO.getFileSystem == source.toNIO.getFileSystem &&
              sourceItem.toNIO.startsWith(source.toNIO) =>
          buildTargets.asScala
      }
      .getOrElse(Iterable.empty)
  }

  def inverseSourceItem(source: AbsolutePath): Option[AbsolutePath] =
    sourceItems.find(item => source.toNIO.startsWith(item.toNIO))

  def originalInverseSourceItem(source: AbsolutePath): Option[AbsolutePath] =
    originalSourceItems.asScala.find(item =>
      source.toNIO.startsWith(item.dealias.toNIO)
    )

  def isInverseDependency(
      query: BuildTargetIdentifier,
      roots: List[BuildTargetIdentifier]
  ): Boolean = {
    BuildTargets.isInverseDependency(
      query,
      roots,
      inverseDependencies.get(_).map(_.toSeq)
    )
  }
  def inverseDependencyLeaves(
      target: BuildTargetIdentifier
  ): collection.Set[BuildTargetIdentifier] = {
    computeInverseDependencies(target).leaves
  }
  def allInverseDependencies(
      target: BuildTargetIdentifier
  ): collection.Set[BuildTargetIdentifier] = {
    computeInverseDependencies(target).visited
  }
  private def computeInverseDependencies(
      target: BuildTargetIdentifier
  ): BuildTargets.InverseDependencies = {
    BuildTargets.inverseDependencies(
      List(target),
      inverseDependencies.get(_).map(_.toSeq)
    )
  }

  def addDependencySource(
      sourcesJar: AbsolutePath,
      target: BuildTargetIdentifier
  ): Unit = {
    sourceJarNameToJarFile(sourcesJar.filename) = sourcesJar
    val acc = inverseDependencySources.getOrElse(sourcesJar, Set.empty)
    inverseDependencySources(sourcesJar) = acc + target
  }

  def sourceJarFile(sourceJarName: String): Option[AbsolutePath] = {
    sourceJarNameToJarFile.get(sourceJarName)
  }

  def inverseDependencySource(
      sourceJar: AbsolutePath
  ): collection.Set[BuildTargetIdentifier] = {
    inverseDependencySources.get(sourceJar).getOrElse(Set.empty)
  }

  def addSourceRoot(root: AbsolutePath): Unit = {
    isSourceRoot.add(root)
  }
  def sourceRoots: Iterable[AbsolutePath] = {
    isSourceRoot.asScala
  }

  def isInsideSourceRoot(path: AbsolutePath): Boolean = {
    !isSourceRoot.contains(path) &&
    isSourceRoot.asScala.exists { root => path.toNIO.startsWith(root.toNIO) }
  }

  def resetConnections(
      idToConn: List[(BuildTargetIdentifier, BuildServerConnection)]
  ): Unit = {
    targetToConnection.clear()
    idToConn.foreach { case (id, conn) => targetToConnection.put(id, conn) }
  }

  def buildServerOf(
      id: BuildTargetIdentifier
  ): Option[BuildServerConnection] = {
    ammoniteBuildServer(id).orElse(targetToConnection.get(id))
  }
}

object BuildTargets {

  def withAmmonite(ammonite: () => Ammonite): BuildTargets = {
    val ammoniteBuildServerF =
      (id: BuildTargetIdentifier) =>
        if (Ammonite.isAmmBuildTarget(id)) ammonite().buildServer
        else None

    new BuildTargets(ammoniteBuildServerF)
  }

  def withoutAmmonite: BuildTargets =
    new BuildTargets(_ => None)

  def isInverseDependency(
      query: BuildTargetIdentifier,
      roots: List[BuildTargetIdentifier],
      inverseDeps: BuildTargetIdentifier => Option[Seq[BuildTargetIdentifier]]
  ): Boolean = {
    val isVisited = mutable.Set.empty[BuildTargetIdentifier]
    @tailrec
    def loop(toVisit: List[BuildTargetIdentifier]): Boolean =
      toVisit match {
        case Nil => false
        case head :: tail =>
          if (head == query) true
          else if (isVisited(head)) false
          else {
            isVisited += head
            inverseDeps(head) match {
              case Some(next) =>
                loop(next.toList ++ tail)
              case None =>
                loop(tail)
            }
          }
      }
    loop(roots)
  }

  /**
   * Given an acyclic graph and a root target, returns the leaf nodes that depend on the root target.
   *
   * For example, returns `[D, E, C]` given the following graph with root A: {{{
   *      A
   *    ^   ^
   *    |   |
   *    B   C
   *   ^ ^
   *   | |
   *   D E
   * }}}
   */
  def inverseDependencies(
      root: List[BuildTargetIdentifier],
      inverseDeps: BuildTargetIdentifier => Option[Seq[BuildTargetIdentifier]]
  ): InverseDependencies = {
    val isVisited = mutable.Set.empty[BuildTargetIdentifier]
    val leaves = mutable.Set.empty[BuildTargetIdentifier]
    def loop(toVisit: List[BuildTargetIdentifier]): Unit =
      toVisit match {
        case Nil => ()
        case head :: tail =>
          if (!isVisited(head)) {
            isVisited += head
            inverseDeps(head) match {
              case Some(next) =>
                loop(next.toList)
              case None =>
                // Only add leaves of the tree to the result to minimize the number
                // of targets that we compile. If `B` depends on `A`, it's faster
                // in Bloop to compile only `B` than `A+B`.
                leaves += head
            }
            loop(tail)
          }
      }
    loop(root)
    InverseDependencies(isVisited, leaves)
  }

  case class InverseDependencies(
      visited: collection.Set[BuildTargetIdentifier],
      leaves: collection.Set[BuildTargetIdentifier]
  )

}
