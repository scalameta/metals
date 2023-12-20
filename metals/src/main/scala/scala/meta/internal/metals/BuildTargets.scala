package scala.meta.internal.metals

import java.lang.{Iterable => JIterable}
import java.net.URLClassLoader
import java.nio.file.Path
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.InverseSourcesParams
import ch.epfl.scala.bsp4j.TextDocumentIdentifier

/**
 * In-memory cache for looking up build server metadata.
 */
final class BuildTargets private (
    workspace: AbsolutePath,
    tables: Option[Tables],
) {
  private val dataLock = new Object
  private var data: BuildTargets.DataSeq =
    BuildTargets.DataSeq((new TargetData) :: Nil)
  def allWritableData = data.list

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

      val isScala213Version =
        scalaTarget(t).exists(info => info.scalaBinaryVersion == "2.13")
      if (isScala213Version) score <<= 1

      score
  }

  def sourceItems: Iterable[AbsolutePath] =
    data.iterable.flatMap(_.sourceItemsToBuildTarget.keys)
  def sourceItemsToBuildTargets
      : Iterator[(AbsolutePath, JIterable[BuildTargetIdentifier])] =
    data.fromIterators(_.sourceItemsToBuildTarget.iterator)
  private def allBuildTargetIdsInternal
      : Iterator[(TargetData, BuildTargetIdentifier)] =
    data.fromIterators(d => d.allBuildTargetIds.iterator.map((d, _)))
  def mappedTo(path: AbsolutePath): Option[TargetData.MappedSource] =
    data.fromOptions(_.actualSources.get(path))
  def mappedFrom(path: AbsolutePath): Option[AbsolutePath] =
    data.fromOptions(_.actualSources.collectFirst {
      case (source, mapped) if mapped.path == path => source
    })
  private def findMappedSource(
      mappedPath: AbsolutePath
  ): Option[TargetData.MappedSource] = {
    data
      .fromOptions(_.actualSources.collectFirst {
        case (_, mapped) if mapped.path == mappedPath => mapped
      })
  }
  def mappedLineForServer(mappedPath: AbsolutePath, line: Int): Option[Int] =
    findMappedSource(mappedPath).flatMap(_.lineForServer(line))
  def mappedLineForClient(mappedPath: AbsolutePath, line: Int): Option[Int] =
    findMappedSource(mappedPath).flatMap(_.lineForClient(line))

  def allBuildTargetIds: Seq[BuildTargetIdentifier] =
    allBuildTargetIdsInternal.map(_._2).toVector

  def allTargetRoots: Iterator[AbsolutePath] =
    data.fromIterators(_.allTargetRoots)

  def all: Iterator[BuildTarget] =
    data.fromIterators(_.all)

  def allScala: Iterator[ScalaTarget] =
    data.fromIterators(_.allScala)

  def allJava: Iterator[JavaTarget] =
    data.fromIterators(_.allJava)

  def info(id: BuildTargetIdentifier): Option[BuildTarget] =
    data.fromOptions(_.info(id))

  def targetData(id: BuildTargetIdentifier): Option[TargetData] =
    data.fromOptions(data0 => if (data0.info(id).isEmpty) None else Some(data0))

  def scalaTarget(id: BuildTargetIdentifier): Option[ScalaTarget] =
    data.fromOptions(_.scalaTarget(id))

  def javaTarget(id: BuildTargetIdentifier): Option[JavaTarget] =
    data.fromOptions(_.javaTarget(id))

  def targetJarClasspath(
      id: BuildTargetIdentifier
  ): Option[List[AbsolutePath]] =
    data.fromOptions(_.targetJarClasspath(id))

  def targetClasspath(
      id: BuildTargetIdentifier
  ): Option[List[String]] =
    data.fromOptions(_.targetClasspath(id))

  def targetClassDirectories(
      id: BuildTargetIdentifier
  ): List[String] =
    data.fromIterators(_.targetClassDirectories(id).iterator).toList

  def allWorkspaceJars: Iterator[AbsolutePath] = {
    val isVisited = new ju.HashSet[AbsolutePath]
    data.fromIterators(_.allWorkspaceJars).filter { p =>
      isVisited.add(p)
    }
  }

  def onCreate(source: AbsolutePath): Unit = {
    for {
      buildTargetIds <- sourceBuildTargets(source)
      buildTargetId <- buildTargetIds
      targetData <- targetData(buildTargetId)
    } {
      targetData.onCreate(source)
    }
  }

  def allSourceJars: Iterator[AbsolutePath] =
    data.fromIterators(_.inverseDependencySources.keysIterator)

  def buildTargetSources(
      id: BuildTargetIdentifier
  ): Iterable[AbsolutePath] =
    data
      .fromOptions(_.buildTargetSources.get(id))
      .map(_.asScala)
      .getOrElse(Nil)

  def buildTargetTransitiveSources(
      id: BuildTargetIdentifier
  ): Iterator[AbsolutePath] = {
    for {
      dependency <- buildTargetTransitiveDependencies(id).iterator
      sources <- data.fromOptions(_.buildTargetSources.get(dependency)).iterator
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
    data.fromOptions(_.javaTargetRoot(buildTarget))

  def scalaTargetRoot(
      buildTarget: BuildTargetIdentifier
  ): Option[AbsolutePath] =
    data.fromOptions(_.scalaTargetRoot(buildTarget))

  def workspaceDirectory(
      buildTarget: BuildTargetIdentifier
  ): Option[AbsolutePath] =
    buildServerOf(buildTarget).map(_.workspaceDirectory)

  def canCompile(id: BuildTargetIdentifier): Boolean = {
    targetData(id).exists { data =>
      data.buildTargetInfo
        .get(id)
        .map[Boolean](_.getCapabilities().getCanCompile())
        .getOrElse(true)
    }
  }

  /**
   * Returns the first build target containing this source file.
   */
  def inverseSources(
      source: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    val buildTargets = sourceBuildTargets(source)
    val orSbtBuildTarget =
      buildTargets.getOrElse(sbtBuildScalaTarget(source).toIterable)
    if (orSbtBuildTarget.isEmpty) {
      tables
        .flatMap(_.dependencySources.getBuildTarget(source))
        .orElse(inferBuildTarget(source))
    } else {
      Some(orSbtBuildTarget.maxBy(buildTargetsOrder))
    }
  }

  def inverseSourcesBsp(
      source: AbsolutePath
  )(implicit ec: ExecutionContext): Future[Option[BuildTargetIdentifier]] = {
    inverseSources(source) match {
      case None =>
        val identifier = new TextDocumentIdentifier(
          source.toTextDocumentIdentifier.getUri()
        )
        val params = new InverseSourcesParams(identifier)
        val connections =
          data.fromIterators(_.targetToConnection.values.toIterator).distinct
        val queries = connections.map { connection =>
          connection
            .buildTargetInverseSources(params)
            .map(_.getTargets.asScala.toList)
        }
        Future.sequence(queries).map { results =>
          val target = results.flatten.maxByOption(buildTargetsOrder)
          for {
            tgt <- target
            data <- targetData(tgt)
          } data.addSourceItem(source, tgt)
          target
        }
      case some =>
        Future.successful(some)
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
              allBuildTargetIdsInternal.find { case (_, id) =>
                targetJarClasspath(id).exists(_.contains(jar))
              }
            }
          fromJar.map { case (data0, id) =>
            data0.addSourceItem(source, id)
            id
          }
      }
    }
  }

  def findByDisplayName(name: String): Option[BuildTarget] = {
    data
      .fromIterators(_.buildTargetInfo.valuesIterator)
      .find(_.getDisplayName() == name)
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
    data
      .fromIterators(_.buildTargetInfo.valuesIterator)
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
      id: BuildTargetIdentifier,
  )
  def inferBuildTarget(
      toplevels: Iterable[Symbol]
  ): Option[InferredBuildTarget] = {
    val classloader = new URLClassLoader(
      allWorkspaceJars.map(_.toNIO.toUri().toURL()).toArray,
      null,
    )
    lazy val classpaths: Seq[(BuildTargetIdentifier, Iterator[AbsolutePath])] =
      allBuildTargetIdsInternal.toVector.map { case (data, id) =>
        id -> data
          .targetClasspath(id)
          .map(_.toAbsoluteClasspath)
          .getOrElse(Iterator.empty)
      }

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
  ): Option[Iterable[BuildTargetIdentifier]] =
    data.fromOptions(_.sourceBuildTargets(sourceItem))

  def inverseSourceItem(source: AbsolutePath): Option[AbsolutePath] =
    sourceItems.find(item => source.toNIO.startsWith(item.toNIO))

  def originalInverseSourceItem(source: AbsolutePath): Option[AbsolutePath] =
    data
      .fromIterators(_.originalSourceItems.asScala.iterator)
      .find(item => source.toNIO.startsWith(item.dealias.toNIO))

  def isInverseDependency(
      query: BuildTargetIdentifier,
      roots: List[BuildTargetIdentifier],
  ): Boolean = {
    BuildTargets.isInverseDependency(
      query,
      roots,
      id => data.fromOptions(_.inverseDependencies.get(id)),
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
      id => data.fromOptions(_.inverseDependencies.get(id)),
    )
  }

  def sourceJarFile(sourceJarName: String): Option[AbsolutePath] =
    data.fromOptions(_.sourceJarNameToJarFile.get(sourceJarName))

  def inverseDependencySource(
      sourceJar: AbsolutePath
  ): collection.Set[BuildTargetIdentifier] = {
    data
      .fromOptions(_.inverseDependencySources.get(sourceJar))
      .getOrElse(Set.empty)
  }

  def sourceRoots: Iterable[AbsolutePath] = {
    data.iterable.flatMap(_.isSourceRoot.asScala)
  }

  def isInsideSourceRoot(path: AbsolutePath): Boolean = {
    data.iterator.exists(_.isSourceRoot.contains(path)) &&
    data.fromIterators(_.isSourceRoot.asScala.iterator).exists { root =>
      path.toNIO.startsWith(root.toNIO)
    }
  }

  def isSourceFile(source: AbsolutePath): Boolean =
    data.iterator.exists(_.isSourceFile(source))

  def checkIfGeneratedSource(source: Path): Boolean =
    data.iterator.exists(_.checkIfGeneratedSource(source))
  def checkIfGeneratedDir(path: AbsolutePath): Boolean =
    data.iterator.exists(_.checkIfGeneratedDir(path))

  def buildServerOf(
      id: BuildTargetIdentifier
  ): Option[BuildServerConnection] =
    data.fromOptions(_.targetToConnection.get(id))

  def addData(data: TargetData): Unit =
    dataLock.synchronized {
      this.data = BuildTargets.DataSeq(data :: this.data.list)
    }
}

object BuildTargets {
  def from(
      workspace: AbsolutePath,
      data: TargetData,
      tables: Tables,
  ): BuildTargets = {
    val targets = new BuildTargets(workspace, Some(tables))
    targets.addData(data)
    targets
  }

  def empty: BuildTargets = new BuildTargets(PathIO.workingDirectory, None)

  def isInverseDependency(
      query: BuildTargetIdentifier,
      roots: List[BuildTargetIdentifier],
      inverseDeps: BuildTargetIdentifier => Option[
        collection.Seq[BuildTargetIdentifier]
      ],
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
      inverseDeps: BuildTargetIdentifier => Option[
        collection.Seq[BuildTargetIdentifier]
      ],
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
          }
          loop(tail)
      }
    loop(root)
    InverseDependencies(isVisited, leaves)
  }

  case class InverseDependencies(
      visited: collection.Set[BuildTargetIdentifier],
      leaves: collection.Set[BuildTargetIdentifier],
  )

  final case class DataSeq(list: List[TargetData]) {
    def iterator: Iterator[TargetData] = list.iterator
    def writableDataIterator: Iterator[TargetData] = list.iterator
    def iterable: Iterable[TargetData] = list.toIterable

    def fromIterators[T](f: TargetData => Iterator[T]): Iterator[T] =
      iterator.flatMap(f)
    def fromOptions[T](f: TargetData => Option[T]): Option[T] =
      fromIterators(f(_).iterator).find(_ => true)
  }

}
