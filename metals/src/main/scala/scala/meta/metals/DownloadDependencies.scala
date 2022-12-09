package scala.meta.metals

import java.nio.file.Files
import java.nio.file.Path

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.logging.MetalsLogger

import bloop.launcher.Launcher
import coursierapi.Dependency

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
      downloadBloop()

    allPaths.distinct.foreach(println)

    // NOTE(olafur): important, Bloop comes last because it does System.exit()
    tryLAunchBloop()
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
        ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
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
    scribe.info("Downloading mtags")
    BuildInfo.supportedScalaVersions.flatMap { scalaVersion =>
      Embedded.downloadMtags(scalaVersion)
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
    Embedded.downloadSemanticdbJavac
  }

  def downloadBloop(): Seq[Path] = {
    scribe.info("Downloading bloop")

    // Try to donwload all artifacts needed for Bloop
    Embedded.downloadDependency(
      Dependency.of(
        "ch.epfl.scala",
        s"bloop-config_" + metalsBinaryVersion,
        BuildInfo.bloopConfigVersion,
      ),
      scalaVersion = Some(BuildInfo.scala213),
    ) ++ Embedded.downloadDependency(
      Dependency.of(
        "ch.epfl.scala",
        s"bloop-launcher-core_" + metalsBinaryVersion,
        BuildInfo.bloopVersion,
      ),
      scalaVersion = Some(BuildInfo.scala213),
    ) ++ Embedded.downloadDependency(
      Dependency.of(
        "ch.epfl.scala",
        s"bloop-frontend_2.12",
        BuildInfo.bloopVersion,
      ),
      scalaVersion = Some(BuildInfo.scala213),
    )

  }

  def tryLAunchBloop(): Unit = {
    // NOTE(olafur): this starts a daemon process for the Bloop server.
    Launcher.main(Array("--skip-bsp-connection", BuildInfo.bloopVersion))
  }
}
