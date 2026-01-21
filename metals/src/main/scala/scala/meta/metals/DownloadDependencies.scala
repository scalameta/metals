package scala.meta.metals

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.FileDecoderProvider
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.debug.server.MetalsDebugToolsResolver
import scala.meta.internal.metals.debug.server.testing.TestInternals
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.debugadapter.ScalaVersion
import coursier.LocalRepositories
import coursier.paths.CoursierPaths

object DownloadDependencies {

  private val metalsBinaryVersion =
    ScalaVersions.scalaBinaryVersionFromFullVersion(BuildInfo.scala213)

  private val complete = new CoursierComplete("3")
  private lazy val allSupportedScala3Versions = complete
    .complete("org.scala-lang:scala3-presentation-compiler_3:")
    .filterNot(_.contains("RC"))
    .filter { version =>
      // dependency on snapshot mtags version from an old snapshot repository
      !Set("3.5.0", "3.4.3", "3.4.2", "3.4.1", "3.4.0")(version)
    }

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

    val filterVersions: String => Boolean =
      args.indexOf("--scala-versions") match {
        case -1 => (_: String) => true
        case index =>
          val versions =
            args.drop(index + 1).takeWhile(!_.startsWith("-")).toSet
          println("Filtering versions: " + versions.mkString(", "))
          (s: String) => versions(s)
      }
    MetalsLogger.updateDefaultFormat()
    val allPaths = downloadMdoc(filterVersions) ++
      downloadScalafmt() ++
      downloadMtags(filterVersions) ++
      downloadSemanticDBScalac(filterVersions) ++
      downloadSemanticDBJavac() ++
      downloadScala() ++
      downloadBloop() ++
      downloadBazelBsp() ++
      downloadCfr() ++
      downloadScala3PresentationCompiler(filterVersions) ++
      downloadAllScalafixVersions(filterVersions) ++
      downloadAllScalaDebugToolVersions(filterVersions) ++
      downloadTestAgent()

    val distinctFiles = allPaths.distinct
    val copyToDest = args.indexOf("--copy-to") match {
      case -1 => None
      case index =>
        Some(args(index + 1))
    }

    copyToDest.foreach { path =>
      copyToDestination(distinctFiles, path)
    }
    distinctFiles.foreach(println)
  }

  def copyToDestination(
      distinctFiles: Seq[Path],
      destinationString: String,
  ): Unit = {
    val destinationPath = AbsolutePath(
      Paths.get(destinationString).toAbsolutePath()
    )
    val ivyDirectoryPath = LocalRepositories.ivy2Local.pattern.chunks
      .takeWhile {
        case _: coursier.ivy.Pattern.Chunk.Const => true
        case _ => false
      }
      .map(_.string)
      .mkString

    val coursierCacheLocation = AbsolutePath(
      CoursierPaths.cacheDirectory().toPath()
    )
    val ivyDirectory = AbsolutePath(Paths.get(URI.create(ivyDirectoryPath)))
    println(s"Copying artifacts to ${destinationPath}")
    distinctFiles.foreach { p =>
      val relative =
        if (p.startsWith(coursierCacheLocation.toNIO))
          AbsolutePath(p).toRelative(coursierCacheLocation)
        else if (p.startsWith(ivyDirectory.toNIO))
          AbsolutePath(p).toRelative(ivyDirectory)
        else throw new Exception(s"Unexpected cache path: $p")
      val target = destinationPath.resolve(relative)
      if (!Files.exists(target.toNIO.getParent))
        Files.createDirectories(target.toNIO.getParent)
      if (Files.exists(target.toNIO)) {
        println(s"Skipping $p, already exists at $target")
      } else {
        println(s"Copied $p to $target")
        Files.copy(p, target.toNIO)
      }
    }

  }

  def downloadScala(): Seq[Path] = {
    scribe.info("Downloading scala library and sources")
    BuildInfo.supportedScala2Versions.flatMap { scalaVersion =>
      Embedded.downloadScalaSources(scalaVersion)
    }
  }

  def downloadMdoc(filterVersions: String => Boolean): Seq[Path] = {
    scribe.info("Downloading mdoc")
    BuildInfo.supportedScala2Versions.filter(filterVersions).flatMap {
      scalaVersion =>
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
      Embedded.dependencyOf(
        "org.scalameta",
        s"scalafmt-cli_" + metalsBinaryVersion,
        BuildInfo.scalafmtVersion,
      ),
      Some(BuildInfo.scala213),
    )
  }

  def downloadMtags(filterVersions: String => Boolean): Seq[Path] = {
    scribe.info("Downloading mtags")
    BuildInfo.supportedScalaVersions.filter(filterVersions).flatMap {
      scalaVersion =>
        Embedded.downloadMtags(scalaVersion, BuildInfo.metalsVersion)
    }
  }

  def downloadScala3PresentationCompiler(
      filterVersions: String => Boolean
  ): Seq[Path] = {
    scribe.info("Downloading Scala 3 presentation compiler")
    allSupportedScala3Versions.filter(filterVersions).flatMap { scalaVersion =>
      Embedded.downloadScala3PresentationCompiler(
        scalaVersion
      ) ++ Embedded.downloadScala3Sources(scalaVersion)
    }
  }

  def downloadSemanticDBScalac(filterVersions: String => Boolean): Seq[Path] = {
    scribe.info("Downloading semanticdb-scalac")
    BuildInfo.supportedScala2Versions.filter(filterVersions).flatMap {
      scalaVersion =>
        Embedded.downloadSemanticdbScalac(scalaVersion)
    }
  }

  def downloadSemanticDBJavac(): Seq[Path] = {
    scribe.info("Downloading semanticdb-javac")
    Embedded.downloadSemanticdbJavac
  }

  def downloadBazelBsp(): Seq[Path] = {
    scribe.info("Downloading bazel-bsp")
    Embedded.downloadDependency(
      BazelBuildTool.dependency,
      None,
    )
  }

  def downloadCfr(): Seq[Path] = {
    scribe.info("Downloading cfr")
    Embedded.downloadDependency(FileDecoderProvider.cfrDependency)
  }

  def downloadAllScalafixVersions(
      filterVersions: String => Boolean
  ): Seq[Path] = {
    scribe.info("Downloading all Scalafix versions")
    val allScalaVersions =
      (allSupportedScala3Versions ++ BuildInfo.supportedScala2Versions).filter(
        filterVersions
      )
    val allToDownload = allScalaVersions.distinct
      .filterNot(_.startsWith("2.11"))
    allToDownload.flatMap(Embedded.downloadScalafix)
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

  def downloadAllScalaDebugToolVersions(
      filterVersions: String => Boolean
  ): Seq[Path] = {
    scribe.info("Downloading all Scala debug tool versions")
    val resolver = new MetalsDebugToolsResolver()
    def downloadExpressionCompiler(scalaVersion: String) =
      Embedded.downloadDependency(
        resolver.expressionCompilerDependency(ScalaVersion(scalaVersion)),
        Some(scalaVersion),
      )

    def downloadDebugDecoderCompiler(scalaVersion: String) =
      Embedded.downloadDependency(
        resolver.debugDecoderDependency(ScalaVersion(scalaVersion)),
        Some(scalaVersion),
      )

    (allSupportedScala3Versions ++ BuildInfo.supportedScala2Versions)
      .filter(filterVersions)
      .filter(scalaVersion => !SemVer.isLaterVersion("3.7.4", scalaVersion))
      .flatMap { scalaVersion =>
        val noExpressionCompiler = Seq("3.6.0", "2.11.12")
        val expressionCompilerJars =
          if (!noExpressionCompiler.contains(scalaVersion))
            downloadExpressionCompiler(scalaVersion)
          else Seq.empty
        val debugDecoderJars =
          if (scalaVersion.startsWith("3."))
            downloadDebugDecoderCompiler(scalaVersion)
          else Seq.empty
        expressionCompilerJars ++ debugDecoderJars
      }
  }

  def downloadTestAgent(): Seq[Path] = {
    scribe.info(s"Downloading test-agent")
    Embedded.downloadDependency(TestInternals.dependency)
  }
}
