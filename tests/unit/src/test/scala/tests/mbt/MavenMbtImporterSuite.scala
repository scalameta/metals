package tests.mbt

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.mbt.importer.MavenMbtImporter
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

import tests.BaseSuite

class MavenMbtImporterSuite extends BaseSuite {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private final class TestMavenMbtImporter(
      root: AbsolutePath,
      version: String,
      baseCommand: List[String] = List("mvn"),
      onRun: (List[String], AbsolutePath) => Future[Int],
  ) extends MavenMbtImporter(
        root,
        shellRunner = null,
        userConfig = () => UserConfiguration(),
      ) {
    override def mavenBaseCommand(): List[String] = baseCommand
    override protected def pluginVersion: String = version
    override protected def runExport(args: List[String]): Future[Int] =
      onRun(args, root)
    override def isBuildRelated(path: AbsolutePath): Boolean =
      path.toNIO.getFileName.toString == "pom.xml"
    override def digest(workspace: AbsolutePath): Option[String] =
      Some(workspace.toString)
  }

  test("release-version-omits-global-settings") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-rel"))
    var recordedArgs: List[String] = Nil
    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1",
      onRun = { (args, ws) =>
        recordedArgs = args
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    assertEquals(
      recordedArgs,
      List(
        "mvn",
        "--quiet",
        "org.scalameta:metals-maven-plugin:2.0.0-RC1:export",
        s"-DmbtOutputFile=${importer.outputPath(workspace)}",
      ),
    )
  }

  test("snapshot-version-adds-and-cleans-up-global-settings") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-snap"))
    val snapshotVersion = "2.0.0-RC1-22-d02f337b-SNAPSHOT"
    var recordedArgs: List[String] = Nil
    var capturedSettingsPath: Option[Path] = None
    var capturedXml: String = ""

    val importer = new TestMavenMbtImporter(
      workspace,
      version = snapshotVersion,
      onRun = { (args, ws) =>
        recordedArgs = args
        val gsIdx = args.indexOf("--global-settings")
        assert(gsIdx >= 0, s"expected --global-settings in $args")
        val settingsPath = Paths.get(args(gsIdx + 1))
        assert(
          Files.isRegularFile(settingsPath),
          s"expected global settings file to exist during export: $settingsPath",
        )
        capturedSettingsPath = Some(settingsPath)
        capturedXml = Files.readString(settingsPath, StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    val settingsPath = capturedSettingsPath.getOrElse(
      fail("expected global settings path to be captured")
    )
    assert(
      !Files.exists(settingsPath),
      s"expected temporary global settings file to be deleted: $settingsPath",
    )
    assertEquals(
      recordedArgs,
      List(
        "mvn",
        "--global-settings",
        settingsPath.toString,
        "--quiet",
        s"org.scalameta:metals-maven-plugin:$snapshotVersion:export",
        s"-DmbtOutputFile=${importer.outputPath(workspace)}",
      ),
    )

    val doc = DocumentBuilderFactory
      .newInstance()
      .newDocumentBuilder()
      .parse(
        new ByteArrayInputStream(capturedXml.getBytes(StandardCharsets.UTF_8))
      )
    assertEquals(
      doc.getElementsByTagName("url").item(0).getTextContent,
      MavenMbtImporter.centralSnapshotsUrl,
    )
    assertEquals(
      doc.getElementsByTagName("activeProfile").item(0).getTextContent,
      "metals-snapshots",
    )
  }

  test("snapshot-version-cleans-up-global-settings-on-failure") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-fail"))
    var capturedSettingsPath: Option[Path] = None

    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      onRun = { (args, _) =>
        val gsIdx = args.indexOf("--global-settings")
        capturedSettingsPath = Some(Paths.get(args(gsIdx + 1)))
        Future.successful(1)
      },
    )

    intercept[Exception] {
      Await.result(importer.extract(workspace), Duration.Inf)
    }

    val settingsPath = capturedSettingsPath.getOrElse(
      fail("expected global settings path to be captured")
    )
    assert(
      !Files.exists(settingsPath),
      s"expected temporary global settings file to be deleted on failure: $settingsPath",
    )
  }

  test("snapshot-version-preserves-explicit-global-settings") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-gs"))
    var recordedArgs: List[String] = Nil

    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      baseCommand = List("mvn", "-gs", "/custom/global-settings.xml"),
      onRun = { (args, ws) =>
        recordedArgs = args
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    assertEquals(
      recordedArgs,
      List(
        "mvn",
        "-gs",
        "/custom/global-settings.xml",
        "--quiet",
        "org.scalameta:metals-maven-plugin:2.0.0-RC1-22-d02f337b-SNAPSHOT:export",
        s"-DmbtOutputFile=${importer.outputPath(workspace)}",
      ),
    )
  }

  test("snapshot-version-preserves-maven-config-global-settings") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-cfg"))
    val mavenConfig = workspace.resolve(".mvn/maven.config").toNIO
    Files.createDirectories(mavenConfig.getParent)
    Files.writeString(
      mavenConfig,
      "# project global settings\n--global-settings=/corp/settings.xml\n",
      StandardCharsets.UTF_8,
    )
    var recordedArgs: List[String] = Nil

    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      onRun = { (args, ws) =>
        recordedArgs = args
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    assert(!recordedArgs.contains("--global-settings"))
  }
}
