package scala.meta.metals

import java.nio.file.Files

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.metals.ScalaVersions

import bloop.launcher.Launcher

object DownloadDependencies {

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
    downloadMdoc()
    downloadScalafmt()
    downloadMtags()
    downloadSemanticDB()
    downloadScala()
    // NOTE(olafur): important, Bloop comes last because it does System.exit()
    downloadBloop()
  }

  def downloadScala(): Unit = {
    scribe.info("Downloading scala library and sources")
    BuildInfo.supportedScala2Versions.foreach { scalaVersion =>
      Embedded.downloadScalaSources(scalaVersion)
    }

    BuildInfo.supportedScala3Versions.foreach { scalaVersion =>
      Embedded.downloadDottySources(scalaVersion)
    }
  }

  def downloadMdoc(): Unit = {
    scribe.info("Downloading mdoc")
    BuildInfo.supportedScala2Versions.foreach { scalaVersion =>
      Embedded.downloadMdoc(
        scalaVersion,
        ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
      )
    }
  }

  def downloadScalafmt(): Unit = {
    scribe.info("Downloading scalafmt")
    val scalafmt = FormattingProvider.newScalafmt()
    val tmp = Files.createTempFile("scalafmt", "Foo.scala")
    val config = Files.createTempFile("scalafmt", ".scalafmt.conf")
    scalafmt.format(config, tmp, "object Foo { }")
    Files.deleteIfExists(tmp)
    Files.deleteIfExists(config)
  }

  def downloadMtags(): Unit = {
    scribe.info("Downloading mtags")
    BuildInfo.supportedScalaVersions.foreach { scalaVersion =>
      Embedded.downloadMtags(scalaVersion)
    }
  }

  def downloadSemanticDB(): Unit = {
    scribe.info("Downloading semanticdb-scalac")
    BuildInfo.supportedScala2Versions.foreach { scalaVersion =>
      Embedded.downloadSemanticdbScalac(scalaVersion)
    }
  }

  def downloadBloop(): Unit = {
    scribe.info("Downloading bloop")
    // NOTE(olafur): this starts a daemon process for the Bloop server.
    Launcher.main(Array("--skip-bsp-connection", BuildInfo.bloopVersion))
  }
}
