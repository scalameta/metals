package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.io.AbsolutePath

import munit.FunSuite

class MavenMbtImporterSuite extends FunSuite {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private def importer(version: String): MavenMbtImporter = {
    val root = AbsolutePath(Files.createTempDirectory("maven-mbt-importer"))
    new MavenMbtImporter(root, null, () => UserConfiguration()) {
      override def mavenBaseCommand(): List[String] = List("mvn")
      override protected def pluginVersion: String = version
      override def isBuildRelated(path: AbsolutePath): Boolean = false
      override def digest(workspace: AbsolutePath): Option[String] = None
    }
  }

  test("release-version-has-no-settings") {
    val maven = importer("2.0.0-RC1")
    val out = maven.outputPath(maven.projectRoot)
    val (args, settings) = maven.prepareExportCommand(out)
    assertEquals(settings, None)
    assertEquals(
      args,
      List(
        "mvn",
        "--quiet",
        "org.scalameta:metals-maven-plugin:2.0.0-RC1:export",
        s"-DmbtOutputFile=$out",
      ),
    )
  }

  test("snapshot-version-adds-global-settings") {
    val version = "2.0.0-RC1-22-d02f337b-SNAPSHOT"
    val maven = importer(version)
    val out = maven.outputPath(maven.projectRoot)
    val (args, settings) = maven.prepareExportCommand(out)
    val file = settings.getOrElse(fail("expected a settings file"))
    try {
      assertEquals(
        args,
        List(
          "mvn",
          "--global-settings",
          file.toString,
          "--quiet",
          s"org.scalameta:metals-maven-plugin:$version:export",
          s"-DmbtOutputFile=$out",
        ),
      )
      val xml = new String(Files.readAllBytes(file), "UTF-8")
      assert(xml.contains(MavenMbtImporter.centralSnapshotsUrl), xml)
    } finally Files.deleteIfExists(file)
  }

  test("snapshot-settings-deleted-when-run-throws") {
    val maven = importer("2.0.0-RC1-SNAPSHOT")
    val result = Await.ready(maven.extract(maven.projectRoot), Duration("10s"))
    assert(result.value.exists(_.isFailure))
    val outDir = maven.outputPath(maven.projectRoot).toNIO.getParent
    val leftovers = Option(outDir.toFile.listFiles())
      .fold(List.empty[String])(_.map(_.getName).toList)
      .filter(_.startsWith("metals-maven-snapshot"))
    assertEquals(leftovers, Nil)
  }
}
