package scala.meta.internal.metals

import java.lang.{Iterable => JIterable}
import java.net.URLClassLoader
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
import ch.epfl.scala.bsp4j.JavacOptionsItem
import ch.epfl.scala.bsp4j.JavacOptionsResult
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
  private val javacTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, JavacOptionsItem]
  private val scalacTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, ScalacOptionsItem]
  private val inverseDependencies =
    TrieMap.empty[BuildTargetIdentifier, ListBuffer[BuildTargetIdentifier]]
  private val buildTargetSources =
    TrieMap.empty[BuildTargetIdentifier, util.Set[AbsolutePath]]
  private val inverseDependencySources =
    TrieMap.empty[AbsolutePath, Set[BuildTargetIdentifier]]
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

      val usesJavac = javacOptions(t).nonEmpty
      if (usesJavac) score <<= 1

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
    javacTargetInfo.clear()
    scalacTargetInfo.clear()
    inverseDependencies.clear()
    buildTargetSources.clear()
    inverseDependencySources.clear()
    isSourceRoot.clear()
  }
  def sourceItems: Iterable[AbsolutePath] = sourceItemsToBuildTarget.keys
  def sourceItemsToBuildTargets
      : Iterator[(AbsolutePath, JIterable[BuildTargetIdentifier])] =
    sourceItemsToBuildTarget.iterator

  private def scalacOptions: Iterable[ScalacOptionsItem] =
    scalacTargetInfo.values
  private def javacOptions: Iterable[JavacOptionsItem] = javacTargetInfo.values

  def allTargets: Iterator[BuildTarget] = buildTargetInfo.values.iterator

  def allBuildTargetIds: Seq[BuildTargetIdentifier] = buildTargetInfo.keys.toSeq

  def allTargetRoots: Iterator[AbsolutePath] = {
    val scalaTargetRoots = for {
      item <- scalacOptions
      scalaInfo <- scalaInfo(item.getTarget)
    } yield (item.targetroot(scalaInfo.getScalaVersion))
    val javaTargetRoots = javacOptions.map(_.targetroot)
    // targets can appear in both javacOptions and scalacOptions
    val allTargetRoots = scalaTargetRoots.toSet ++ javaTargetRoots.toSet
    allTargetRoots.iterator
  }

  def allCommon: Iterator[CommonTarget] =
    allTargets.map(CommonTarget.apply)

  def allScala: Iterator[ScalaTarget] =
    allTargets.flatMap(toScalaTarget)

  def allJava: Iterator[JavaTarget] =
    allTargets.flatMap(toJavaTarget)

  def commonTarget(id: BuildTargetIdentifier): Option[CommonTarget] =
    buildTargetInfo.get(id).map(CommonTarget.apply)

  def scalaTarget(id: BuildTargetIdentifier): Option[ScalaTarget] =
    buildTargetInfo.get(id).flatMap(toScalaTarget)

  def javaTarget(id: BuildTargetIdentifier): Option[JavaTarget] =
    buildTargetInfo.get(id).flatMap(toJavaTarget)

  def targetJarClasspath(
      id: BuildTargetIdentifier
  ): Option[List[AbsolutePath]] = {
    val scalacData = scalacTargetInfo.get(id).map(_.jarClasspath)
    val javacData = javacTargetInfo.get(id).map(_.jarClasspath)
    scalacData
      .flatMap(s => javacData.map(j => (s ::: j).distinct).orElse(scalacData))
      .orElse(javacData)
  }

  private def targetClasspath(
      id: BuildTargetIdentifier
  ): Option[List[String]] = {
    val scalacData = scalacTargetInfo.get(id).map(_.classpath)
    val javacData = javacTargetInfo.get(id).map(_.classpath)
    scalacData
      .flatMap(s => javacData.map(j => (s ::: j).distinct).orElse(scalacData))
      .orElse(javacData)
  }

  def targetClassDirectories(
      id: BuildTargetIdentifier
  ): Option[Iterator[String]] = {
    val scalacData = scalacTargetInfo.get(id).map(_.getClassDirectory)
    val javacData = javacTargetInfo.get(id).map(_.getClassDirectory)
    scalacData
      .flatMap(s =>
        javacData
          .map(j => List(s, j).distinct.iterator)
          .orElse(scalacData.map(Iterator(_)))
      )
      .orElse(javacData.map(Iterator(_)))
  }

  private def toScalaTarget(target: BuildTarget): Option[ScalaTarget] = {
    for {
      scalac <- scalacTargetInfo.get(target.getId)
      scalaTarget <- target.asScalaBuildTarget
    } yield {
      val autoImports = target.asSbtBuildTarget.map(_.getAutoImports.asScala)
      ScalaTarget(
        target,
        scalaTarget,
        scalac,
        autoImports,
        target.isSbtBuild
      )
    }
  }

  private def toJavaTarget(target: BuildTarget): Option[JavaTarget] = {
    for {
      javac <- javacTargetInfo.get(target.getId)
    } yield JavaTarget(target, javac)
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

  def addJavacOptions(result: JavacOptionsResult): Unit = {
    result.getItems.asScala.foreach { item =>
      javacTargetInfo(item.getTarget) = item
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
    javacTargetInfo.get(buildTarget).map(_.targetroot)

  def scalaTargetRoot(
      buildTarget: BuildTargetIdentifier
  ): Option[AbsolutePath] =
    scalacTargetInfo
      .get(buildTarget)
      .flatMap(scalacOptions => {
        scalaInfo(scalacOptions.getTarget).map(scalaBuildTarget =>
          scalacOptions.targetroot(scalaBuildTarget.getScalaVersion)
        )
      })

  def scalacOptions(
      buildTarget: BuildTargetIdentifier
  ): Option[ScalacOptionsItem] =
    scalacTargetInfo.get(buildTarget)

  def javacOptions(
      buildTarget: BuildTargetIdentifier
  ): Option[JavacOptionsItem] =
    javacTargetInfo.get(buildTarget)

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
    val readonly = workspace.resolve(Directories.readonly)
    source.toRelativeInside(readonly) match {
      case Some(rel) =>
        val names = rel.toNIO.iterator().asScala.toList.map(_.filename)
        names match {
          case Directories.dependenciesName :: jarName :: _ =>
            // match build target by source jar name
            inverseDependencySources
              .collectFirst {
                case (path, ids) if path.filename == jarName && ids.nonEmpty =>
                  ids.head
              }
          case _ =>
            None
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
    val acc = inverseDependencySources.getOrElse(sourcesJar, Set.empty)
    inverseDependencySources(sourcesJar) = acc + target
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
