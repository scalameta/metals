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

import coursier.Fetch
import coursier.Repositories
import coursier.core.Classifier
import coursier.core.Dependency
import coursier.core.Module
import coursier.core.ModuleName
import coursier.core.Organization
import coursier.error.CoursierError
import coursier.maven.SbtMavenRepository

object JarSourcesProvider {

  private val sbtRegex = "sbt-(.*)".r

  def fetchSources(
      jars: Seq[String]
  )(implicit ec: ExecutionContext): Future[Seq[String]] = {
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
      Future
        .sequence {
          dependencies.map { dep =>
            fetchDependencySources(dep)
              .recover { case error: CoursierError =>
                scribe.warn(
                  s"Could not fetch dependency sources for $dep, error: $error"
                )
                Nil
              }
          }
        }
        .recover {
          case _: TimeoutException =>
            scribe.warn(s"Timeout when fetching dependency sources.")
            Nil
          case NonFatal(e) =>
            scribe.warn(s"Could not fetch dependency sources, error: $e.")
            Nil
        }
        .map(_.flatten.map(_.toUri().toString()))

    val existingSources = haveSources.map(sourcesPath)
    fetchedSources.map(_ ++ existingSources)

  }

  private def sbtFallback(jar: AbsolutePath): Option[Dependency] = {
    val filename = jar.filename.stripSuffix(".jar")
    filename match {
      case sbtRegex(versionStr) =>
        Try(SemVer.Version.fromString(versionStr)) match {
          case Success(version) if version.toString == versionStr =>
            val module = Module(
              Organization("org.scala-sbt"),
              ModuleName("sbt"),
              Map.empty,
            )
            Some(Dependency(module, versionStr))
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
    val properties = (xml \ "properties")

    def getProperty(name: String) =
      properties.map(node => (node \ name).text).find(_.nonEmpty).map(name -> _)

    Option.when(groupId.nonEmpty && version.nonEmpty && artifactId.nonEmpty) {
      val scalaVersion = getProperty("scalaVersion").toMap
      val sbtVersion = getProperty("sbtVersion").toMap
      val attributes = (scalaVersion ++ sbtVersion)
      Dependency(
        Module(Organization(groupId), ModuleName(artifactId), attributes),
        version,
      )
    }
  }

  private val sbtMaven = SbtMavenRepository(Repositories.central)
  private val metalsPluginSnapshots = SbtMavenRepository(
    Repositories.sonatype("public")
  )

  def fetchDependencySources(
      dependency: Dependency
  )(implicit ec: ExecutionContext): Future[List[Path]] = {
    val repositories =
      List(Repositories.central, sbtMaven, metalsPluginSnapshots)
    Fetch()
      .withRepositories(repositories)
      .withDependencies(Seq(dependency))
      .addClassifiers(Classifier.sources)
      .future()
      .map(_.map(_.toPath()).toList)
      .withTimeout(5, TimeUnit.MINUTES)
  }

}
