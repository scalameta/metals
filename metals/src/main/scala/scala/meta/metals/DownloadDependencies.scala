package scala.meta.metals

import java.nio.file.Files
import java.nio.file.Path

import scala.util.control.NonFatal

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.logging.MetalsLogger

import coursierapi.Dependency
import coursierapi.error.SimpleResolutionError

object DownloadDependencies {

  private val metalsBinaryVersion =
    ScalaVersions.scalaBinaryVersionFromFullVersion(BuildInfo.scala213)

  /**
   * A main class that populates the Coursier download cache with Metals dependencies.
   *
   * The `org.scalameta:metals` artifact on Maven Central doesn't directly
   * depend on all of its dependencies. Some dependencies like Scalafmt are
   * dynamic depending on the Scalafmt version that users have configured in
   * their workspace. This main method does a best-effort to try and
   * pre-download as much as possible.
   *
   * @param args ignored.
   */
  def main(args: Array[String]): Unit = {
    MetalsLogger.updateDefaultFormat()
    val allPaths = downloadMdoc() ++
      downloadScalafmt() ++
      downloadMtags() ++
      downloadSemanticDBScalac() ++
      downloadSemanticDBJavac() ++
      downloadScala() ++
      downloadBloop() ++
      downloadBazelBsp()

    allPaths.distinct.foreach(println)
  }

  def downloadScala(): Seq[Path] = {
    scribe.info("Downloading scala library and sources")
    BuildInfo.supportedScala2Versions.flatMap { scalaVersion =>
      Embedded.downloadScalaSources(scalaVersion)
    } ++ BuildInfo.supportedScala3Versions.flatMap { scalaVersion =>
      Embedded.downloadScala3Sources(scalaVersion)
    }
  }

  def downloadMdoc(): Seq[Path] = {
    scribe.info("Downloading mdoc")
    BuildInfo.supportedScala2Versions.flatMap { scalaVersion =>
      Embedded.downloadMdoc(
        ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion),
        scalaVersion = None,
      )
    }
  }

  def downloadScalafmt(): Seq[Path] = {
    scribe.info("Downloading scalafmt")
    val scalafmt = FormattingProvider.newScalafmt()
    val tmp = Files.createTempFile("scalafmt", "Foo.scala")
    val config = Files.createTempFile("scalafmt", ".scalafmt.conf")
    Files.write(
      config,
      s"""|version = ${BuildInfo.scalafmtVersion}
          |runner.dialect = scala3""".stripMargin.getBytes,
    )
    scalafmt.format(config, tmp, "object Foo { }")
    Files.deleteIfExists(tmp)
    Files.deleteIfExists(config)
    Embedded.downloadDependency(
      Dependency.of(
        "org.scalameta",
        s"scalafmt-cli_" + metalsBinaryVersion,
        BuildInfo.scalafmtVersion,
      ),
      scalaVersion = Some(BuildInfo.scala213),
    )
  }

  def downloadMtags(): Seq[Path] = {
    val version = BuildInfo.metalsVersion
    scribe.info(s"Downloading mtags $version")
    BuildInfo.supportedScalaVersions.flatMap { scalaVersion =>
      val artifact = s"mtags_${scalaVersion}:$version"
      try {
        val result = Embedded.downloadMtags(scalaVersion, version)
        scribe.info(s"Downloaded $artifact")
        result
      } catch {
        case _: SimpleResolutionError =>
          scribe.error(
            s"Not found $artifact"
          )
          Nil
        case NonFatal(ex) =>
          scribe.error(s"Unexpected error downloading $artifact", ex)
          Nil
      }
    }
  }

  def downloadSemanticDBScalac(): Seq[Path] = {
    scribe.info("Downloading semanticdb-scalac")
    BuildInfo.supportedScala2Versions.flatMap { scalaVersion =>
      Embedded.downloadSemanticdbScalac(scalaVersion)
    }
  }

  def downloadSemanticDBJavac(): Seq[Path] = {
    scribe.info("Downloading semanticdb-javac")
    Embedded.downloadSemanticdbJavac()
  }

  def downloadBazelBsp(): Seq[Path] = {
    scribe.info("Downloading bazel-bsp")
    Embedded.downloadDependency(
      BazelBuildTool.dependency,
      None,
    )
  }

  def downloadBloop(): Seq[Path] = {
    val version = BloopServers.defaultBloopVersion
    scribe.info(s"Downloading Bloop $version")
    BloopServers.fetchBloop(version) match {
      case Left(ex) =>
        throw new Exception(s"Could not pre-download Bloop $version", ex)
      case Right(files) => files.map(_.toPath)
    }
  }
}
