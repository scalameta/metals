package tests.mbt

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import javax.xml.parsers.DocumentBuilderFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.mbt.importer.MavenMbtImporter
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

import org.w3c.dom.Document
import tests.BaseSuite

class MavenMbtImporterSuite extends BaseSuite {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private class TestMavenMbtImporter(
      root: AbsolutePath,
      version: String,
      baseCommand: List[String] = List("mvn"),
      env: Map[String, String] = Map.empty,
      userHomeDir: Path = Files.createTempDirectory("maven-mbt-empty-home"),
      onRun: (List[String], AbsolutePath) => Future[Int],
  ) extends MavenMbtImporter(
        root,
        shellRunner = null,
        userConfig = () => UserConfiguration(),
      ) {
    override def mavenBaseCommand(): List[String] = baseCommand
    override protected def pluginVersion: String = version
    override protected def environment: Map[String, String] = env
    override protected def defaultUserHome: String = userHomeDir.toString
    override protected def runExport(args: List[String]): Future[Int] =
      onRun(args, root)
    override def isBuildRelated(path: AbsolutePath): Boolean =
      Option(path.toNIO.getFileName).exists(_.toString == "pom.xml")
    override def digest(workspace: AbsolutePath): Option[String] =
      Some(workspace.toString)
  }

  private def parseXml(xml: String): Document = {
    val dbf = DocumentBuilderFactory.newInstance()
    dbf.setNamespaceAware(true)
    dbf
      .newDocumentBuilder()
      .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
  }

  private def tagValues(doc: Document, tag: String): List[String] = {
    val nodes = doc.getElementsByTagName(tag)
    List.tabulate(nodes.getLength)(i => nodes.item(i).getTextContent.trim)
  }

  test("release-version-omits-settings") {
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

  test("snapshot-version-adds-and-cleans-up-secure-temp-settings") {
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
        val sIdx = args.indexOf("--settings")
        assert(sIdx >= 0, s"expected --settings in $args")
        val settingsPath = Paths.get(args(sIdx + 1))
        assert(
          Files.isRegularFile(settingsPath),
          s"expected user settings file to exist during export: $settingsPath",
        )
        if (
          FileSystems.getDefault.supportedFileAttributeViews().contains("posix")
        ) {
          val perms = PosixFilePermissions.toString(
            Files.getPosixFilePermissions(settingsPath)
          )
          assertEquals(perms, "rw-------")
        }
        capturedSettingsPath = Some(settingsPath)
        capturedXml = Files.readString(settingsPath, StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    val settingsPath = capturedSettingsPath.getOrElse(
      fail("expected user settings path to be captured")
    )
    assert(
      !Files.exists(settingsPath),
      s"expected temporary user settings file to be deleted: $settingsPath",
    )
    assertEquals(
      recordedArgs,
      List(
        "mvn",
        "--settings",
        settingsPath.toString,
        "--quiet",
        s"org.scalameta:metals-maven-plugin:$snapshotVersion:export",
        s"-DmbtOutputFile=${importer.outputPath(workspace)}",
      ),
    )

    val doc = parseXml(capturedXml)
    assertEquals(
      tagValues(doc, "url"),
      List(MavenMbtImporter.centralSnapshotsUrl),
    )
    assertEquals(
      tagValues(doc, "activeProfile"),
      List("metals-snapshots"),
    )
  }

  test("snapshot-version-cleans-up-settings-on-failure") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-fail"))
    var capturedSettingsPath: Option[Path] = None

    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      onRun = { (args, _) =>
        val sIdx = args.indexOf("--settings")
        capturedSettingsPath = Some(Paths.get(args(sIdx + 1)))
        Future.successful(1)
      },
    )

    intercept[Exception] {
      Await.result(importer.extract(workspace), Duration.Inf)
    }

    val settingsPath = capturedSettingsPath.getOrElse(
      fail("expected user settings path to be captured")
    )
    assert(
      !Files.exists(settingsPath),
      s"expected temporary user settings file to be deleted on failure: $settingsPath",
    )
  }

  test("setup-failures-return-failed-future-without-throwing-synchronously") {
    val workspaceWithFileMetals =
      AbsolutePath(Files.createTempDirectory("maven-mbt-dir-fail"))
    Files.writeString(
      workspaceWithFileMetals.resolve(".metals").toNIO,
      "not-a-directory",
      StandardCharsets.UTF_8,
    )
    val dirFailImporter = new TestMavenMbtImporter(
      workspaceWithFileMetals,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      onRun = (_, _) => Future.successful(ExitCodes.Success),
    )
    val dirFailFuture = dirFailImporter.extract(workspaceWithFileMetals)
    intercept[FileAlreadyExistsException] {
      Await.result(dirFailFuture, Duration.Inf)
    }

    val workspaceSettingsFail =
      AbsolutePath(Files.createTempDirectory("maven-mbt-settings-fail"))
    val settingsFailImporter = new TestMavenMbtImporter(
      workspaceSettingsFail,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      onRun = (_, _) => Future.successful(ExitCodes.Success),
    ) {
      override protected def createSnapshotSettings(
          baseArgs: List[String]
      ): (List[String], Path) =
        throw new IOException("temp dir full")
    }
    val settingsFailFuture = settingsFailImporter.extract(workspaceSettingsFail)
    intercept[IOException] {
      Await.result(settingsFailFuture, Duration.Inf)
    }

    val workspaceSyncRunFail =
      AbsolutePath(Files.createTempDirectory("maven-mbt-sync-run-fail"))
    var createdSettings: Option[Path] = None
    val syncRunFailImporter = new TestMavenMbtImporter(
      workspaceSyncRunFail,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      onRun = { (args, _) =>
        val sIdx = args.indexOf("--settings")
        createdSettings = Some(Paths.get(args(sIdx + 1)))
        throw new IllegalStateException("launch failed synchronously")
      },
    )
    val syncRunFailFuture = syncRunFailImporter.extract(workspaceSyncRunFail)
    intercept[IllegalStateException] {
      Await.result(syncRunFailFuture, Duration.Inf)
    }
    val cleanedPath = createdSettings.getOrElse(
      fail("expected settings path before synchronous launch failure")
    )
    assert(
      !Files.exists(cleanedPath),
      s"expected temporary user settings file to be deleted: $cleanedPath",
    )
  }

  test(
    "snapshot-version-merges-default-user-settings-and-avoids-profile-id-collision"
  ) {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-user"))
    val userHome = Files.createTempDirectory("fake-user-home")
    val m2Dir = userHome.resolve(".m2")
    Files.createDirectories(m2Dir)
    val nsUri = "http://maven.apache.org/SETTINGS/1.2.0"
    Files.writeString(
      m2Dir.resolve("settings.xml"),
      s"""|<?xml version="1.0" encoding="UTF-8"?>
          |<settings xmlns="$nsUri">
          |  <proxies>
          |    <proxy>
          |      <id>corp-proxy</id>
          |      <host>proxy.corp.example</host>
          |      <port>8080</port>
          |    </proxy>
          |  </proxies>
          |  <servers>
          |    <server>
          |      <id>corp-server</id>
          |      <username>alice</username>
          |    </server>
          |  </servers>
          |  <mirrors>
          |    <mirror>
          |      <id>corp-mirror</id>
          |      <url>https://repo.corp.example/maven2</url>
          |      <mirrorOf>central</mirrorOf>
          |    </mirror>
          |  </mirrors>
          |  <profiles>
          |    <profile>
          |      <id>metals-snapshots</id>
          |    </profile>
          |  </profiles>
          |  <activeProfiles>
          |    <activeProfile>corp-profile</activeProfile>
          |  </activeProfiles>
          |</settings>
          |""".stripMargin,
      StandardCharsets.UTF_8,
    )

    var capturedXml: String = ""
    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      userHomeDir = userHome,
      onRun = { (args, ws) =>
        val sIdx = args.indexOf("--settings")
        capturedXml =
          Files.readString(Paths.get(args(sIdx + 1)), StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    val doc = parseXml(capturedXml)
    assertEquals(tagValues(doc, "host"), List("proxy.corp.example"))
    assertEquals(tagValues(doc, "username"), List("alice"))
    assertEquals(
      tagValues(doc, "url"),
      List(
        "https://repo.corp.example/maven2",
        MavenMbtImporter.centralSnapshotsUrl,
      ),
    )
    assertEquals(
      tagValues(doc, "activeProfile"),
      List("corp-profile", "metals-snapshots-1"),
    )
    val profileNodes = doc.getElementsByTagName("profile")
    assertEquals(profileNodes.getLength, 2)
    for (i <- 0 until profileNodes.getLength) {
      assertEquals(profileNodes.item(i).getNamespaceURI, nsUri)
    }
  }

  test("snapshot-version-strips-and-merges-cli-settings-with-first-wins") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-cli-s"))
    val ciDir = workspace.resolve("ci").toNIO
    Files.createDirectories(ciDir)
    Files.writeString(
      ciDir.resolve("first.xml"),
      """|<settings>
         |  <servers>
         |    <server>
         |      <id>first-ci-server</id>
         |    </server>
         |  </servers>
         |</settings>
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )
    Files.writeString(
      ciDir.resolve("second.xml"),
      """|<settings>
         |  <servers>
         |    <server>
         |      <id>second-ci-server</id>
         |    </server>
         |  </servers>
         |</settings>
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )

    val wrapperCmd = List(
      "java",
      "-server",
      s"-Dmaven.multiModuleProjectDirectory=$workspace",
      "-cp",
      "/tmp/maven-wrapper.jar",
      "org.apache.maven.wrapper.MavenWrapperMain",
      "-P",
      "ci-profile",
      "--color",
      "-s",
      "ci/first.xml",
      "--settings=ci/second.xml",
      "-sattached-ignored.xml",
      "--",
      "-s",
      "kept-after-dash-dash",
    )

    var recordedArgs: List[String] = Nil
    var capturedXml: String = ""
    var capturedTempSettings: String = ""

    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      baseCommand = wrapperCmd,
      onRun = { (args, ws) =>
        recordedArgs = args
        val sIdx = args.indexOf("--settings")
        capturedTempSettings = args(sIdx + 1)
        capturedXml = Files.readString(
          Paths.get(capturedTempSettings),
          StandardCharsets.UTF_8,
        )
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    assertEquals(
      recordedArgs,
      List(
        "java",
        "-server",
        s"-Dmaven.multiModuleProjectDirectory=$workspace",
        "-cp",
        "/tmp/maven-wrapper.jar",
        "org.apache.maven.wrapper.MavenWrapperMain",
        "-P",
        "ci-profile",
        "--color",
        "--settings",
        capturedTempSettings,
        "--quiet",
        "org.scalameta:metals-maven-plugin:2.0.0-RC1-22-d02f337b-SNAPSHOT:export",
        s"-DmbtOutputFile=${importer.outputPath(workspace)}",
        "--",
        "-s",
        "kept-after-dash-dash",
      ),
    )

    assert(capturedXml.contains("first-ci-server"))
    assert(!capturedXml.contains("second-ci-server"))
    assert(capturedXml.contains(MavenMbtImporter.centralSnapshotsUrl))
  }

  test(
    "snapshot-version-merges-maven-config-settings-and-preserves-global-settings"
  ) {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-cfg"))
    val mvnDir = workspace.resolve(".mvn").toNIO
    val customDir = mvnDir.resolve("dir with spaces")
    Files.createDirectories(customDir)
    Files.writeString(
      customDir.resolve("project-settings.xml"),
      """|<settings>
         |  <mirrors>
         |    <mirror>
         |      <id>maven-config-mirror</id>
         |      <url>https://mirror.project.internal/maven2</url>
         |      <mirrorOf>central</mirrorOf>
         |    </mirror>
         |  </mirrors>
         |</settings>
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )
    Files.writeString(
      mvnDir.resolve("maven.config"),
      """|# project maven options
         |--global-settings=/corp/global-settings.xml
         |-s
         |.mvn/dir with spaces/project-settings.xml
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )

    var recordedArgs: List[String] = Nil
    var capturedXml: String = ""

    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      baseCommand = List("mvn", "-gs", "/corp/cli-global.xml"),
      onRun = { (args, ws) =>
        recordedArgs = args
        val sIdx = args.indexOf("--settings")
        capturedXml =
          Files.readString(Paths.get(args(sIdx + 1)), StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    assert(!recordedArgs.contains("--global-settings"))
    assert(recordedArgs.contains("-gs"))
    assert(recordedArgs.contains("/corp/cli-global.xml"))
    assert(capturedXml.contains("maven-config-mirror"))
    assert(capturedXml.contains(MavenMbtImporter.centralSnapshotsUrl))
  }

  test("snapshot-version-fails-when-explicit-settings-missing") {
    val workspace =
      AbsolutePath(Files.createTempDirectory("maven-mbt-missing-s"))

    val cliImporter = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      baseCommand = List("mvn", "-s", "does-not-exist.xml"),
      onRun = (_, _) => Future.successful(ExitCodes.Success),
    )
    intercept[FileNotFoundException] {
      Await.result(cliImporter.extract(workspace), Duration.Inf)
    }

    val mvnDir = workspace.resolve(".mvn").toNIO
    Files.createDirectories(mvnDir)
    Files.writeString(
      mvnDir.resolve("maven.config"),
      "--settings=missing-from-config.xml\n",
      StandardCharsets.UTF_8,
    )
    val cfgImporter = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      onRun = (_, _) => Future.successful(ExitCodes.Success),
    )
    intercept[FileNotFoundException] {
      Await.result(cfgImporter.extract(workspace), Duration.Inf)
    }
  }

  test("snapshot-version-resolves-effective-user-home-overrides") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-uh"))
    val defaultHome = Files.createTempDirectory("uh-default")
    val jvmCfgHome = Files.createTempDirectory("uh-jvmcfg")
    val mavenOptsHome = Files.createTempDirectory("uh-mavenopts")
    val jvmArgHome = Files.createTempDirectory("uh-jvmarg")
    val cliHome = Files.createTempDirectory("uh-cli")

    def writeMarkerSettings(home: Path, markerId: String): Unit = {
      val m2 = home.resolve(".m2")
      Files.createDirectories(m2)
      Files.writeString(
        m2.resolve("settings.xml"),
        s"""|<settings>
            |  <servers>
            |    <server><id>$markerId</id></server>
            |  </servers>
            |</settings>
            |""".stripMargin,
        StandardCharsets.UTF_8,
      )
    }

    writeMarkerSettings(defaultHome, "from-default-home")
    writeMarkerSettings(jvmCfgHome, "from-jvm-config")
    writeMarkerSettings(mavenOptsHome, "from-maven-opts")
    writeMarkerSettings(jvmArgHome, "from-jvm-arg")
    writeMarkerSettings(cliHome, "from-cli-define")

    val mvnDir = workspace.resolve(".mvn").toNIO
    Files.createDirectories(mvnDir)
    Files.writeString(
      mvnDir.resolve("jvm.config"),
      s"-Duser.home=$jvmCfgHome\n",
      StandardCharsets.UTF_8,
    )

    // 1. .mvn/jvm.config overrides defaultUserHome
    val xmlJvmCfg = MavenMbtImporter.buildSnapshotUserSettingsXml(
      List("mvn"),
      workspace,
      Map.empty,
      defaultHome.toString,
    )
    assert(xmlJvmCfg.contains("from-jvm-config"))

    // 2. MAVEN_OPTS overrides .mvn/jvm.config
    val xmlMavenOpts = MavenMbtImporter.buildSnapshotUserSettingsXml(
      List("mvn"),
      workspace,
      Map("MAVEN_OPTS" -> s"-Xmx512m -Duser.home=$mavenOptsHome"),
      defaultHome.toString,
    )
    assert(xmlMavenOpts.contains("from-maven-opts"))

    // 3. MavenWrapperMain JVM -Duser.home arg overrides defaultUserHome
    val wrapperCmd = List(
      "java",
      s"-Duser.home=$jvmArgHome",
      "-cp",
      "/tmp/maven-wrapper.jar",
      "org.apache.maven.wrapper.MavenWrapperMain",
    )
    val xmlJvmArg = MavenMbtImporter.buildSnapshotUserSettingsXml(
      wrapperCmd,
      workspace,
      Map.empty,
      defaultHome.toString,
    )
    assert(xmlJvmArg.contains("from-jvm-arg"))

    // 4. CLI -Duser.home overrides JVM -Duser.home / MAVEN_OPTS
    val xmlCli = MavenMbtImporter.buildSnapshotUserSettingsXml(
      wrapperCmd :+ s"-Duser.home=$cliHome",
      workspace,
      Map("MAVEN_OPTS" -> s"-Duser.home=$mavenOptsHome"),
      defaultHome.toString,
    )
    assert(xmlCli.contains("from-cli-define"))
  }

  test("snapshot-version-falls-back-when-user-settings-has-doctype") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-xxe"))
    val userHome = Files.createTempDirectory("fake-user-xxe")
    val m2Dir = userHome.resolve(".m2")
    Files.createDirectories(m2Dir)
    Files.writeString(
      m2Dir.resolve("settings.xml"),
      """|<?xml version="1.0" encoding="UTF-8"?>
         |<!DOCTYPE settings [ <!ENTITY xxe "disallowed"> ]>
         |<settings/>
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )

    var capturedXml: String = ""
    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      userHomeDir = userHome,
      onRun = { (args, ws) =>
        val sIdx = args.indexOf("--settings")
        capturedXml =
          Files.readString(Paths.get(args(sIdx + 1)), StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    assertEquals(capturedXml, MavenMbtImporter.snapshotPluginSettingsXml)
  }
}
