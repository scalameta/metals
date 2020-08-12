package scala.meta.internal.metals

import java.lang.{Iterable => JIterable}
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import ch.epfl.scala.bsp4j.ScalacOptionsResult
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
  private val scalacTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, ScalacOptionsItem]
  private val inverseDependencies =
    TrieMap.empty[BuildTargetIdentifier, ListBuffer[BuildTargetIdentifier]]
  private val buildTargetSources =
    TrieMap.empty[BuildTargetIdentifier, util.Set[AbsolutePath]]
  private val inverseDependencySources =
    TrieMap.empty[AbsolutePath, BuildTargetIdentifier]
  private val isSourceRoot =
    ConcurrentHashSet.empty[AbsolutePath]
  // if workspace contains symlinks, original source items are kept here and source items dealiased
  private val originalSourceItems = ConcurrentHashSet.empty[AbsolutePath]

  private val targetToConnection =
    new mutable.HashMap[BuildTargetIdentifier, BuildServerConnection]

  val buildTargetsOrder: BuildTargetIdentifier => Int = {
    (t: BuildTargetIdentifier) =>
      var score = 1

      val isSupportedScalaVersion = scalaInfo(t).exists(t =>
        ScalaVersions.isSupportedScalaVersion(t.getScalaVersion())
      )
      if (isSupportedScalaVersion) score <<= 2

      val isJVM = scalacOptions(t).exists(_.isJVM)
      if (isJVM) score <<= 1

      // note(@tgodzik) once the support for Scala 3 is on par with Scala 2 this can be removed
      val isScala2 = scalaInfo(t).exists(info =>
        !ScalaVersions.isScala3Version(info.getScalaVersion())
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
    scalacTargetInfo.clear()
    inverseDependencies.clear()
    buildTargetSources.clear()
    inverseDependencySources.clear()
    isSourceRoot.clear()
  }
  def sourceItems: Iterable[AbsolutePath] =
    sourceItemsToBuildTarget.keys
  def sourceItemsToBuildTargets
      : Iterator[(AbsolutePath, JIterable[BuildTargetIdentifier])] =
    sourceItemsToBuildTarget.iterator
  def scalacOptions: Iterable[ScalacOptionsItem] =
    scalacTargetInfo.values

  def allBuildTargetIds: Seq[BuildTargetIdentifier] =
    all.toSeq.map(_.info.getId())
  def all: Iterator[ScalaTarget] =
    for {
      (_, target) <- buildTargetInfo.iterator
      scalaTarget <- toScalaTarget(target)
    } yield scalaTarget

  def scalaTarget(id: BuildTargetIdentifier): Option[ScalaTarget] =
    for {
      target <- buildTargetInfo.get(id)
      scalaTarget <- toScalaTarget(target)
    } yield scalaTarget

  private def toScalaTarget(target: BuildTarget): Option[ScalaTarget] = {
    for {
      scalac <- scalacTargetInfo.get(target.getId)
      scalaTarget <- target.asScalaBuildTarget
    } yield {
      val autoImports = target.asSbtBuildTarget.map(_.getAutoImports.asScala)
      ScalaTarget(target, scalaTarget, scalac, autoImports)
    }
  }

  def allWorkspaceJars: Iterator[AbsolutePath] = {
    val isVisited = new ju.HashSet[AbsolutePath]()
    Iterator(
      for {
        target <- all
        classpathEntry <- target.scalac.classpath
        if classpathEntry.isJar
        if isVisited.add(classpathEntry)
      } yield classpathEntry,
      PackageIndex.bootClasspath.iterator
    ).flatten
  }

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

  def addScalacOptions(result: ScalacOptionsResult): Unit = {
    result.getItems.asScala.foreach { item =>
      scalacTargetInfo(item.getTarget) = item
    }
  }

  def info(
      buildTarget: BuildTargetIdentifier
  ): Option[BuildTarget] =
    buildTargetInfo.get(buildTarget)
  def scalaInfo(
      buildTarget: BuildTargetIdentifier
  ): Option[ScalaBuildTarget] =
    info(buildTarget).flatMap(_.asScalaBuildTarget)

  def scalacOptions(
      buildTarget: BuildTargetIdentifier
  ): Option[ScalacOptionsItem] =
    scalacTargetInfo.get(buildTarget)

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
   * - extract toplevel symbol definitions from the source code.
   * - find a jar file from any classfile that defines one of the toplevel
   *   symbols.
   * - find the build target which has that jar file in it's classpath.
   *
   * Otherwise if it's a jar file we find a build target it belongs to.
   *
   * This approach is not glamorous but it seems to work reasonably well.
   */
  def inferBuildTarget(
      source: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    if (source.isDependencySource(workspace)) {
      Try(unsafeInferBuildTarget(source)).getOrElse(None)
    } else {
      // else it can be a source file inside a jar
      val fromJar = jarPath(source)
        .flatMap { jar =>
          all.find { scalaTarget =>
            scalaTarget.jarClasspath.contains(jar)
          }
        }
        .map(_.id)
      fromJar.foreach(addSourceItem(source, _))
      fromJar
    }
  }

  def findByDisplayName(name: String): Option[BuildTarget] = {
    buildTargetInfo.values.find(_.getDisplayName() == name)
  }

  private def jarPath(source: AbsolutePath): Option[AbsolutePath] = {
    val filesystem = source.toNIO.getFileSystem()
    if (filesystem.provider().getScheme().equals("jar")) {
      Some(
        AbsolutePath(
          Paths.get(filesystem.toString.replace("-sources.jar", ".jar"))
        )
      )
    } else {
      None
    }
  }

  /**
   * Returns meta build target for `*.sbt` or `*.scala`  files.
   * It selects build target by directory of its connection
   *   because `*.sbt` and `*.scala` aren't included in `sourceFiles` set
   */
  private def sbtBuildScalaTarget(
      file: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    val targetMetaBuildDir =
      if (file.isSbt) file.parent.resolve("project") else file.parent
    buildTargetInfo.values
      .find { target =>
        val isMetaBuild = target.getDataKind == "sbt"
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

  private def unsafeInferBuildTarget(
      source: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    val input = source.toInput
    val toplevels = Mtags
      .allToplevels(input)
      .occurrences
      .map(occ => Symbol(occ.symbol).toplevel)
      .toSet
    inferBuildTarget(toplevels).map { inferred =>
      // Persist inferred result to avoid re-computing it again and again.
      tables.foreach(_.dependencySources.setBuildTarget(source, inferred.id))
      inferred.id
    }
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
    lazy val classpaths =
      all.map(i => i.id -> i.scalac.classpath.toSeq).toSeq
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
    BuildTargets.isInverseDependency(query, roots, inverseDependencies.get)
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
    BuildTargets.inverseDependencies(List(target), inverseDependencies.get)
  }

  def addDependencySource(
      sourcesJar: AbsolutePath,
      target: BuildTargetIdentifier
  ): Unit = {
    inverseDependencySources(sourcesJar) = target
  }

  def inverseDependencySource(
      sourceJar: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    inverseDependencySources.get(sourceJar)
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
