package scala.meta.internal.metals.debug.server

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.JvmTarget
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

class DebugeeParamsCreator(buildTargets: BuildTargets) {
  def create(id: BuildTargetIdentifier): Either[String, DebugeeProject] = {
    for {
      target <- buildTargets
        .jvmTarget(id)
        .toRight(s"No build target $id found.")
      data <- buildTargets
        .targetData(id)
        .toRight(s"No data for build target $id found.")
    } yield {

      val libraries = data.buildTargetDependencyModules
        .get(id)
        .filter(_.nonEmpty)
        .getOrElse(Nil)
      val debugLibs = libraries.flatMap(createLibrary(_))
      val includedInLibs = debugLibs.map(_.absolutePath).toSet

      val classpath = buildTargets.targetJarClasspath(id).getOrElse(Nil)

      val filteredClassPath = classpath.collect {
        case path if !includedInLibs(path.toNIO) => UnmanagedEntry(path.toNIO)
      }.toList

      val modules = buildTargets
        .allInverseDependencies(id)
        .flatMap(buildTargets.jvmTarget)
        .map(createModule(_))
        .toSeq

      val scalaVersion = buildTargets.scalaTarget(id).map(_.scalaVersion)

      new DebugeeProject(
        scalaVersion,
        target.displayName,
        modules,
        debugLibs,
        filteredClassPath,
      )
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

  def createModule(target: JvmTarget): Module = {
    val (scalaVersion, scalacOptions) =
      target match {
        case scalaTarget: ScalaTarget =>
          (
            Some(ScalaVersion(scalaTarget.scalaVersion)),
            scalaTarget.scalac.getOptions().asScala.toSeq,
          )
        case _ => (None, Nil)
      }
    new Module(
      target.displayName,
      scalaVersion,
      scalacOptions,
      target.classDirectory.toAbsolutePath.toNIO,
      sources(target.id),
    )
  }

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
