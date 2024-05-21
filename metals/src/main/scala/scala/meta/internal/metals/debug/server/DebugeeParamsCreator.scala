package scala.meta.internal.metals.debug.server

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.JavaTarget
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTarget

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.MavenDependencyModule
import ch.epfl.scala.debugadapter.Library
import ch.epfl.scala.debugadapter.Module
import ch.epfl.scala.debugadapter.ScalaVersion
import ch.epfl.scala.debugadapter.SourceDirectory
import ch.epfl.scala.debugadapter.SourceJar
import ch.epfl.scala.debugadapter.StandaloneSourceFile
import ch.epfl.scala.debugadapter.UnmanagedEntry

class DebugeeParamsCreator(buildTargets: BuildTargets)(implicit
    ec: ExecutionContext
) {
  def create(id: BuildTargetIdentifier): Option[Future[DebugeeProject]] = {
    val optScalaTarget = buildTargets.scalaTarget(id)
    val optJavaTarget = buildTargets.javaTarget(id)
    for {
      name <- optScalaTarget
        .map(_.displayName)
        .orElse(optJavaTarget.map(_.displayName))
      data <- buildTargets.targetData(id)
    } yield {

      val libraries = data.buildTargetDependencyModules
        .get(id)
        .filter(_.nonEmpty)
        .getOrElse(Nil)
      val debugLibs = libraries.flatMap(createLibrary(_))
      val includedInLibs = debugLibs.map(_.absolutePath).toSet

      val cancelPromise: Promise[Unit] = Promise()

      for (
        classpath <- buildTargets
          .targetClasspath(id, cancelPromise)
          .getOrElse(Future.successful(Nil))
          .map(
            _.filter(_.endsWith(".jar")).toAbsoluteClasspath.map(_.toNIO).toSeq
          )
      ) yield {

        val filteredClassPath = classpath.collect {
          case path if !includedInLibs(path) => UnmanagedEntry(path)
        }.toList

        val modules = buildTargets
          .allInverseDependencies(id)
          .flatMap(id =>
            buildTargets.scalaTarget(id).map(createModule(_)).orElse {
              buildTargets.javaTarget(id).map(createModule(_))
            }
          )
          .toSeq

        new DebugeeProject(
          buildTargets.scalaTarget(id).map(_.scalaVersion),
          name,
          modules,
          debugLibs,
          filteredClassPath,
        )
      }
    }
  }

  def createLibrary(lib: MavenDependencyModule): Option[Library] = {
    def getWithClassifier(s: String) =
      Option(lib.getArtifacts())
        .flatMap(_.asScala.find(_.getClassifier() == s))
        .flatMap(_.getUri().toAbsolutePathSafe)
    for {
      sources <- getWithClassifier("sources")
      jar <- getWithClassifier(null)
    } yield new Library(
      lib.getName(),
      lib.getVersion(),
      jar.toNIO,
      Seq(SourceJar(sources.toNIO)),
    )
  }

  def createModule(target: ScalaTarget): Module = {
    val scalaVersion = ScalaVersion(target.scalaVersion)
    new Module(
      target.displayName,
      Some(scalaVersion),
      target.scalac.getOptions().asScala.toSeq,
      target.classDirectory.toAbsolutePath.toNIO,
      sources(target.id),
    )
  }

  def createModule(target: JavaTarget) =
    new Module(
      target.displayName,
      None,
      Nil,
      target.classDirectory.toAbsolutePath.toNIO,
      sources(target.id),
    )

  private def sources(id: BuildTargetIdentifier) =
    buildTargets.sourceItemsToBuildTargets
      .filter(_._2.iterator.asScala.contains(id))
      .collect { case (sourcePath, _) =>
        if (sourcePath.isDirectory) {
          SourceDirectory(sourcePath.toNIO)
        } else {
          StandaloneSourceFile(
            sourcePath.toNIO,
            sourcePath.toNIO.getFileName.toString,
          )
        }
      }
      .toSeq
}

case class DebugeeProject(
    scalaVersion: Option[String],
    name: String,
    modules: Seq[Module],
    libraries: Seq[Library],
    unmanagedEntries: Seq[UnmanagedEntry],
)
