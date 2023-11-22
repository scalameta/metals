package scala.meta.internal.metals

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal
import scala.xml.XML

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.error.CoursierError

object JarSourcesProvider {

  private val sbtRegex = "sbt-(.*)".r

  def fetchSources(jars: Seq[String])(implicit ec: ExecutionContext): Future[Seq[String]] = {
    def sourcesPath(jar: String) = s"${jar.stripSuffix(".jar")}-sources.jar"

    val (haveSources, toDownload) = jars.partition { jar =>
      sourcesPath(jar).toAbsolutePathSafe.exists(_.exists)
    }

    val dependencies = toDownload.flatMap { jarPath =>
      val pomPath = s"${jarPath.stripSuffix(".jar")}.pom"
      val dependency =
        for {
          pom <- pomPath.toAbsolutePathSafe
          if pom.exists
          dependency <- getDependency(pom)
        } yield dependency

      dependency.orElse { jarPath.toAbsolutePathSafe.flatMap(sbtFallback) }
    }.distinct

    val fetchedSources =
      Future.sequence {
        dependencies.map{ dep =>
          fetchDependencySources(dep)
          .recover {
            case error : CoursierError =>
              scribe.warn(s"Could not fetch dependency sources for $dep, error: $error")
              Nil
          }
        }
      }.recover {
        case _: TimeoutException =>
          scribe.warn(s"Timeout when fetching dependency sources.")
          Nil
        case NonFatal(e) =>
          scribe.warn(s"Could not fetch dependency sources, error: $e.")
          Nil
      }.map(_.flatten.map(_.toUri().toString()))

    val existingSources = haveSources.map(sourcesPath)
    fetchedSources.map(_ ++ existingSources)

  }

  private def sbtFallback(jar: AbsolutePath): Option[Dependency] = {
    val filename = jar.filename.stripSuffix(".jar")
    filename match {
      case sbtRegex(versionStr) =>
        Try(SemVer.Version.fromString(versionStr)) match {
          case Success(version) if version.toString == versionStr =>
            Some(Dependency.of("org.scala-sbt", "sbt", versionStr))
          case _ => None
        }
      case _ => None
    }
  }

  private def getDependency(pom: AbsolutePath) = {
    val xml = XML.loadFile(pom.toFile)
    val groupId = (xml \ "groupId").text
    val version = (xml \ "version").text
    val artifactId = (xml \ "artifactId").text
    Option
      .when(groupId.nonEmpty && version.nonEmpty && artifactId.nonEmpty) {
        Dependency.of(groupId, artifactId, version)
      }
      .filterNot(dep => isSbtDap(dep) || isMetalsPlugin(dep))
  }

  private def isSbtDap(dependency: Dependency) = {
    dependency.getModule().getOrganization() == "ch.epfl.scala" &&
    dependency.getModule().getName() == "sbt-debug-adapter" &&
    dependency.getVersion() == BuildInfo.debugAdapterVersion
  }

  private def isMetalsPlugin(dependency: Dependency) = {
    dependency.getModule().getOrganization() == "org.scalameta" &&
    dependency.getModule().getName() == "sbt-metals" &&
    dependency.getVersion() == BuildInfo.metalsVersion
  }

  private def fetchDependencySources(
      dependency: Dependency
  )(implicit ec: ExecutionContext): Future[List[Path]] = Future {
    Fetch
      .create()
      .withDependencies(dependency)
      .addClassifiers("sources")
      .fetchResult()
      .getFiles()
      .asScala
      .map(_.toPath())
      .toList
  }.withTimeout(5, TimeUnit.MINUTES)

}
