package tests.mbt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import javax.xml.parsers.DocumentBuilderFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try
import scala.util.Using

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
      onRun: (List[String], AbsolutePath) => Future[Int],
  ) extends MavenMbtImporter(
        root,
        shellRunner = null,
        userConfig = () => UserConfiguration(),
      ) {
    override def mavenBaseCommand(): List[String] = baseCommand
    override protected def pluginVersion: String = version
    override protected def environment: Map[String, String] = env
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
      override protected def createSnapshotGlobalSettings(
          baseArgs: List[String]
      ): Path =
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
        val gsIdx = args.indexOf("--global-settings")
        createdSettings = Some(Paths.get(args(gsIdx + 1)))
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
      s"expected temporary global settings file to be deleted: $cleanedPath",
    )
  }

  test("snapshot-version-merges-maven-home-global-settings") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-home"))
    val mavenHome = Files.createTempDirectory("fake-maven-home")
    val confDir = mavenHome.resolve("conf")
    Files.createDirectories(confDir)
    val nsUri = "http://maven.apache.org/SETTINGS/1.2.0"
    Files.writeString(
      confDir.resolve("settings.xml"),
      s"""|<?xml version="1.0" encoding="UTF-8"?>
          |<settings xmlns="$nsUri">
          |  <proxies>
          |    <proxy>
          |      <id>corp-proxy</id>
          |      <host>proxy.corp.example</host>
          |      <port>8080</port>
          |    </proxy>
          |  </proxies>
          |  <mirrors>
          |    <mirror>
          |      <id>corp-mirror</id>
          |      <url>https://repo.corp.example/maven2</url>
          |      <mirrorOf>central</mirrorOf>
          |    </mirror>
          |  </mirrors>
          |  <profiles>
          |    <profile>
          |      <id>corp-profile</id>
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
      env = Map("MAVEN_HOME" -> mavenHome.toString),
      onRun = { (args, ws) =>
        val gsIdx = args.indexOf("--global-settings")
        capturedXml =
          Files.readString(Paths.get(args(gsIdx + 1)), StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    val doc = parseXml(capturedXml)
    assertEquals(tagValues(doc, "host"), List("proxy.corp.example"))
    assertEquals(
      tagValues(doc, "url"),
      List(
        "https://repo.corp.example/maven2",
        MavenMbtImporter.centralSnapshotsUrl,
      ),
    )
    assertEquals(
      tagValues(doc, "activeProfile"),
      List("corp-profile", "metals-snapshots"),
    )
    val profileNodes = doc.getElementsByTagName("profile")
    assertEquals(profileNodes.getLength, 2)
    for (i <- 0 until profileNodes.getLength) {
      assertEquals(profileNodes.item(i).getNamespaceURI, nsUri)
    }
  }

  test("snapshot-version-resolves-mvn-on-path-for-global-settings") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-path"))
    val mavenDist = Files.createTempDirectory("fake-maven-dist")
    val distBin = mavenDist.resolve("bin")
    val distConf = mavenDist.resolve("conf")
    Files.createDirectories(distBin)
    Files.createDirectories(distConf)
    val mvnScript = distBin.resolve(if (isWindows) "mvn.cmd" else "mvn")
    Files.writeString(mvnScript, "#!/bin/sh\n", StandardCharsets.UTF_8)
    mvnScript.toFile.setExecutable(true)
    Files.writeString(
      distConf.resolve("settings.xml"),
      """|<settings>
         |  <servers>
         |    <server>
         |      <id>corp-server</id>
         |      <username>alice</username>
         |    </server>
         |  </servers>
         |</settings>
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )

    val linkDir = Files.createTempDirectory("fake-path-bin")
    val pathEntry =
      Try {
        val link = linkDir.resolve(mvnScript.getFileName)
        Files.createSymbolicLink(link, mvnScript)
        linkDir
      }.getOrElse(distBin)

    var capturedXml: String = ""
    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      env = Map("PATH" -> pathEntry.toString),
      onRun = { (args, ws) =>
        val gsIdx = args.indexOf("--global-settings")
        capturedXml =
          Files.readString(Paths.get(args(gsIdx + 1)), StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    val doc = parseXml(capturedXml)
    assertEquals(tagValues(doc, "username"), List("alice"))
    assertEquals(
      tagValues(doc, "url"),
      List(MavenMbtImporter.centralSnapshotsUrl),
    )
    assertEquals(tagValues(doc, "activeProfile"), List("metals-snapshots"))
  }

  test("snapshot-version-falls-back-when-global-settings-has-doctype") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt-xxe"))
    val mavenHome = Files.createTempDirectory("fake-maven-xxe")
    val confDir = mavenHome.resolve("conf")
    Files.createDirectories(confDir)
    Files.writeString(
      confDir.resolve("settings.xml"),
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
      env = Map("MAVEN_HOME" -> mavenHome.toString),
      onRun = { (args, ws) =>
        val gsIdx = args.indexOf("--global-settings")
        capturedXml =
          Files.readString(Paths.get(args(gsIdx + 1)), StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    assertEquals(capturedXml, MavenMbtImporter.snapshotPluginSettingsXml)
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

  test("snapshot-version-merges-maven-wrapper-main-distribution-settings") {
    val workspace =
      AbsolutePath(Files.createTempDirectory("maven-mbt-wrapper-main"))
    val tempLauncherDir = Files.createTempDirectory("maven-mbt-launcher-tmp")
    val wrapperJar = tempLauncherDir.resolve("maven-wrapper.jar")
    Files.writeString(wrapperJar, "", StandardCharsets.UTF_8)

    val distUrl =
      "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
    Files.writeString(
      tempLauncherDir.resolve("maven-wrapper.properties"),
      s"distributionUrl=$distUrl\n",
      StandardCharsets.UTF_8,
    )

    // MavenWrapperMain overwrites temporary -Dmaven.home with the unpacked wrapper distribution,
    // so any settings under tempLauncherDir/conf must NOT override the wrapper distribution settings.
    val decoyConf = tempLauncherDir.resolve("conf")
    Files.createDirectories(decoyConf)
    Files.writeString(
      decoyConf.resolve("settings.xml"),
      """|<settings>
         |  <proxies>
         |    <proxy>
         |      <id>decoy-temp-proxy</id>
         |    </proxy>
         |  </proxies>
         |</settings>
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )

    val mavenUserHome = Files.createTempDirectory("maven-mbt-user-home")
    val uriHash = Integer.toHexString(new URI(distUrl).hashCode())
    val wrapperConfDir = mavenUserHome
      .resolve("wrapper/dists/apache-maven-3.9.9-bin")
      .resolve(uriHash)
      .resolve("apache-maven-3.9.9/conf")
    Files.createDirectories(wrapperConfDir)
    Files.writeString(
      wrapperConfDir.resolve("settings.xml"),
      """|<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
         |  <proxies>
         |    <proxy>
         |      <id>wrapper-corp-proxy</id>
         |      <active>true</active>
         |      <protocol>http</protocol>
         |      <host>proxy.wrapper.internal</host>
         |      <port>8080</port>
         |    </proxy>
         |  </proxies>
         |  <mirrors>
         |    <mirror>
         |      <id>wrapper-corp-mirror</id>
         |      <mirrorOf>external:*</mirrorOf>
         |      <url>https://nexus.wrapper.internal/maven-public/</url>
         |    </mirror>
         |  </mirrors>
         |</settings>
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )

    val wrapperBaseCommand = List(
      "java",
      "-Dfile.encoding=UTF-8",
      s"-Dmaven.multiModuleProjectDirectory=$workspace",
      s"-Dmaven.home=$tempLauncherDir",
      "-cp",
      wrapperJar.toString,
      "org.apache.maven.wrapper.MavenWrapperMain",
    )

    var capturedXml: String = ""
    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      baseCommand = wrapperBaseCommand,
      env = Map("MAVEN_USER_HOME" -> mavenUserHome.toString),
      onRun = { (args, ws) =>
        val gsIdx = args.indexOf("--global-settings")
        capturedXml =
          Files.readString(Paths.get(args(gsIdx + 1)), StandardCharsets.UTF_8)
        val out = ws.resolve(".metals/mbt-maven.json").toNIO
        Files.writeString(out, "{}", StandardCharsets.UTF_8)
        Future.successful(ExitCodes.Success)
      },
    )

    Await.result(importer.extract(workspace), Duration.Inf)

    val doc = parseXml(capturedXml)
    assert(capturedXml.contains("wrapper-corp-proxy"))
    assert(capturedXml.contains("wrapper-corp-mirror"))
    assert(!capturedXml.contains("decoy-temp-proxy"))
    assertEquals(
      tagValues(doc, "url"),
      List(
        "https://nexus.wrapper.internal/maven-public/",
        MavenMbtImporter.centralSnapshotsUrl,
      ),
    )
    assertEquals(
      tagValues(doc, "activeProfile"),
      List("metals-snapshots"),
    )
  }

  test("snapshot-version-merges-mvnw-script-and-cached-zip-settings") {
    val workspace =
      AbsolutePath(Files.createTempDirectory("maven-mbt-mvnw-script"))
    val wrapperProps =
      workspace.resolve(".mvn/wrapper/maven-wrapper.properties").toNIO
    Files.createDirectories(wrapperProps.getParent)
    val rawUrl =
      "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
    val repoUrl = "https://mirror.corp.internal/maven2"
    val effectiveUrl =
      "https://mirror.corp.internal/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
    Files.writeString(
      wrapperProps,
      s"distributionUrl=$rawUrl\n",
      StandardCharsets.UTF_8,
    )

    // 1. Test script-only mvnw layout (<mavenUserHome>/wrapper/dists/apache-maven-3.9.9/<strHash>/conf/settings.xml)
    val scriptM2 = Files.createTempDirectory("maven-mbt-script-m2")
    val strHash = Integer.toHexString(effectiveUrl.hashCode())
    val scriptConf = scriptM2
      .resolve("wrapper/dists/apache-maven-3.9.9")
      .resolve(strHash)
      .resolve("conf")
    Files.createDirectories(scriptConf)
    Files.writeString(
      scriptConf.resolve("settings.xml"),
      """|<settings>
         |  <proxies>
         |    <proxy>
         |      <id>mvnw-script-proxy</id>
         |    </proxy>
         |  </proxies>
         |</settings>
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )

    val scriptXml = MavenMbtImporter.buildSnapshotGlobalSettingsXml(
      List("./mvnw"),
      workspace,
      Map(
        "MAVEN_USER_HOME" -> scriptM2.toString,
        "MVNW_REPOURL" -> s"$repoUrl/",
      ),
    )
    assert(scriptXml.contains("mvnw-script-proxy"))
    assert(scriptXml.contains(MavenMbtImporter.centralSnapshotsUrl))

    // 2. Test cached wrapper zip before unpacking (<mavenUserHome>/wrapper/dists/apache-maven-3.9.9-bin/<uriHash>/apache-maven-3.9.9-bin.zip)
    val zipM2 = Files.createTempDirectory("maven-mbt-zip-m2")
    val uriHash = Integer.toHexString(new URI(rawUrl).hashCode())
    val zipPath = zipM2
      .resolve("wrapper/dists/apache-maven-3.9.9-bin")
      .resolve(uriHash)
      .resolve("apache-maven-3.9.9-bin.zip")
    Files.createDirectories(zipPath.getParent)
    Using.resource(new ZipOutputStream(Files.newOutputStream(zipPath))) { zos =>
      zos.putNextEntry(new ZipEntry("apache-maven-3.9.9/conf/settings.xml"))
      zos.write(
        """|<settings>
           |  <proxies>
           |    <proxy>
           |      <id>cached-zip-proxy</id>
           |    </proxy>
           |  </proxies>
           |</settings>
           |""".stripMargin.getBytes(StandardCharsets.UTF_8)
      )
      zos.closeEntry()
    }

    val zipXml = MavenMbtImporter.buildSnapshotGlobalSettingsXml(
      List("./mvnw"),
      workspace,
      Map("MAVEN_USER_HOME" -> zipM2.toString),
    )
    assert(zipXml.contains("cached-zip-proxy"))
    assert(zipXml.contains(MavenMbtImporter.centralSnapshotsUrl))
  }

  private def buildStubClassworldsJar(): Array[Byte] = {
    val compiler = ToolProvider.getSystemJavaCompiler()
    assert(compiler != null, "Expected JDK system Java compiler in unit tests")
    val srcDir = Files.createTempDirectory("stub-classworlds-src")
    val pkgDir =
      srcDir.resolve("org/codehaus/plexus/classworlds/launcher")
    Files.createDirectories(pkgDir)
    val javaFile = pkgDir.resolve("Launcher.java")
    Files.writeString(
      javaFile,
      """|package org.codehaus.plexus.classworlds.launcher;
         |public class Launcher {
         |  public static void main(String[] args) {}
         |}
         |""".stripMargin,
      StandardCharsets.UTF_8,
    )
    val compileCode = compiler.run(
      null,
      null,
      null,
      "--release",
      "8",
      javaFile.toString,
    )
    assertEquals(compileCode, 0)
    val classBytes = Files.readAllBytes(pkgDir.resolve("Launcher.class"))
    val baos = new ByteArrayOutputStream()
    Using.resource(new ZipOutputStream(baos)) { zos =>
      zos.putNextEntry(
        new ZipEntry(
          "org/codehaus/plexus/classworlds/launcher/Launcher.class"
        )
      )
      zos.write(classBytes)
      zos.closeEntry()
    }
    baos.toByteArray
  }

  test("snapshot-version-bootstraps-uncached-custom-wrapper-distribution") {
    val workspace =
      AbsolutePath(Files.createTempDirectory("maven-mbt-uncached-wrapper"))
    val tempLauncherDir =
      Files.createTempDirectory("maven-mbt-uncached-launcher")
    val wrapperJar = tempLauncherDir.resolve("maven-wrapper.jar")
    val wrapperRes = Option(getClass.getResourceAsStream("/maven-wrapper.jar"))
    wrapperRes match {
      case Some(in) =>
        Using.resource(in) { stream =>
          Files.copy(stream, wrapperJar)
        }
      case None =>
        val repoJar = Paths.get("metals/src/main/resources/maven-wrapper.jar")
        Files.copy(repoJar, wrapperJar)
    }

    val launcherJarBytes = buildStubClassworldsJar()
    val distZipBytes = {
      val baos = new ByteArrayOutputStream()
      Using.resource(new ZipOutputStream(baos)) { zos =>
        zos.putNextEntry(
          new ZipEntry(
            "apache-maven-3.9.9-corp/boot/plexus-classworlds-2.8.0.jar"
          )
        )
        zos.write(launcherJarBytes)
        zos.closeEntry()

        zos.putNextEntry(new ZipEntry("apache-maven-3.9.9-corp/bin/m2.conf"))
        zos.write(
          "main is org.apache.maven.cli.MavenCli from plexus.core\n".getBytes(
            StandardCharsets.UTF_8
          )
        )
        zos.closeEntry()

        zos.putNextEntry(new ZipEntry("apache-maven-3.9.9-corp/bin/mvn"))
        zos.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(
          new ZipEntry("apache-maven-3.9.9-corp/conf/settings.xml")
        )
        zos.write(
          """|<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
             |  <proxies>
             |    <proxy>
             |      <id>uncached-remote-proxy</id>
             |      <active>true</active>
             |      <protocol>http</protocol>
             |      <host>proxy.uncached.internal</host>
             |      <port>3128</port>
             |    </proxy>
             |  </proxies>
             |  <mirrors>
             |    <mirror>
             |      <id>uncached-remote-mirror</id>
             |      <mirrorOf>external:*</mirrorOf>
             |      <url>https://artifactory.uncached.internal/maven/</url>
             |    </mirror>
             |  </mirrors>
             |  <profiles>
             |    <profile>
             |      <id>uncached-remote-repo-profile</id>
             |    </profile>
             |  </profiles>
             |  <activeProfiles>
             |    <activeProfile>uncached-remote-repo-profile</activeProfile>
             |  </activeProfiles>
             |</settings>
             |""".stripMargin.getBytes(StandardCharsets.UTF_8)
        )
        zos.closeEntry()
      }
      baos.toByteArray
    }

    val downloads = new AtomicInteger(0)
    val server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val serverThread = new Thread(() => {
      var running = true
      while (running && !server.isClosed) {
        Try(server.accept()).toOption match {
          case None =>
            running = false
          case Some(client) =>
            Try {
              Using.resource(client) { sock =>
                val in = sock.getInputStream
                val headerBuf = new ByteArrayOutputStream()
                var matchedEnd = false
                while (!matchedEnd) {
                  val b = in.read()
                  if (b == -1) matchedEnd = true
                  else {
                    headerBuf.write(b)
                    val arr = headerBuf.toByteArray
                    val len = arr.length
                    if (
                      len >= 4 &&
                      arr(len - 4) == '\r' &&
                      arr(len - 3) == '\n' &&
                      arr(len - 2) == '\r' &&
                      arr(len - 1) == '\n'
                    ) matchedEnd = true
                  }
                }
                downloads.incrementAndGet()
                val out = sock.getOutputStream
                val headers =
                  s"HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${distZipBytes.length}\r\nConnection: close\r\n\r\n"
                out.write(headers.getBytes(StandardCharsets.US_ASCII))
                out.write(distZipBytes)
                out.flush()
              }
            }
        }
      }
    })
    serverThread.setDaemon(true)
    serverThread.start()

    try {
      val port = server.getLocalPort
      val distUrl =
        s"http://127.0.0.1:$port/maven2/org/apache/maven/apache-maven/3.9.9-corp/apache-maven-3.9.9-corp-bin.zip"
      Files.writeString(
        tempLauncherDir.resolve("maven-wrapper.properties"),
        s"distributionUrl=$distUrl\n",
        StandardCharsets.UTF_8,
      )

      val mavenUserHome =
        Files.createTempDirectory("maven-mbt-uncached-user-home")
      assert(!Files.exists(mavenUserHome.resolve("wrapper")))

      val javaBin = Paths
        .get(
          System.getProperty("java.home"),
          "bin",
          if (isWindows) "java.exe" else "java",
        )
        .toString
      val wrapperBaseCommand = List(
        javaBin,
        "-Dfile.encoding=UTF-8",
        s"-Dmaven.multiModuleProjectDirectory=$workspace",
        s"-Dmaven.home=$tempLauncherDir",
        "-cp",
        wrapperJar.toString,
        "org.apache.maven.wrapper.MavenWrapperMain",
      )
      val testEnv = Map("MAVEN_USER_HOME" -> mavenUserHome.toString)

      assert(
        MavenMbtImporter.needsWrapperBootstrap(
          wrapperBaseCommand,
          workspace,
          testEnv,
        )
      )

      var recordedCommands: List[List[String]] = Nil
      var capturedXml: String = ""
      val importer = new TestMavenMbtImporter(
        workspace,
        version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
        baseCommand = wrapperBaseCommand,
        env = testEnv,
        onRun = { (args, ws) =>
          recordedCommands = recordedCommands :+ args
          if (args.contains("--version")) {
            assert(!args.contains("--global-settings"))
            val pb = new ProcessBuilder(args*)
            pb.directory(ws.toNIO.toFile)
            pb.environment().put("MAVEN_USER_HOME", mavenUserHome.toString)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            Using.resource(proc.getInputStream)(_.readAllBytes())
            Future.successful(proc.waitFor())
          } else {
            val gsIdx = args.indexOf("--global-settings")
            assert(gsIdx >= 0, s"Expected --global-settings in $args")
            capturedXml = Files.readString(
              Paths.get(args(gsIdx + 1)),
              StandardCharsets.UTF_8,
            )
            val out = ws.resolve(".metals/mbt-maven.json").toNIO
            Files.writeString(out, "{}", StandardCharsets.UTF_8)
            Future.successful(ExitCodes.Success)
          }
        },
      )

      Await.result(importer.extract(workspace), Duration.Inf)

      assertEquals(downloads.get(), 1)
      assertEquals(recordedCommands.length, 2)
      assertEquals(recordedCommands.head, wrapperBaseCommand :+ "--version")

      val doc = parseXml(capturedXml)
      assert(capturedXml.contains("uncached-remote-proxy"))
      assert(capturedXml.contains("uncached-remote-mirror"))
      assertEquals(
        tagValues(doc, "url"),
        List(
          "https://artifactory.uncached.internal/maven/",
          MavenMbtImporter.centralSnapshotsUrl,
        ),
      )
      assertEquals(
        tagValues(doc, "activeProfile"),
        List("uncached-remote-repo-profile", "metals-snapshots"),
      )

      // Once unpacked in wrapper/dists, subsequent imports skip bootstrap and reuse cached settings.
      assert(
        !MavenMbtImporter.needsWrapperBootstrap(
          wrapperBaseCommand,
          workspace,
          testEnv,
        )
      )
      Await.result(importer.extract(workspace), Duration.Inf)
      assertEquals(downloads.get(), 1)
      assertEquals(recordedCommands.length, 3)
    } finally {
      Try(server.close())
    }
  }

  test("snapshot-version-fails-when-uncached-wrapper-bootstrap-fails") {
    val workspace =
      AbsolutePath(Files.createTempDirectory("maven-mbt-bootstrap-fail"))
    val wrapperProps =
      workspace.resolve(".mvn/wrapper/maven-wrapper.properties").toNIO
    Files.createDirectories(wrapperProps.getParent)
    Files.writeString(
      wrapperProps,
      "distributionUrl=https://unreachable.corp.internal/apache-maven-3.9.9-corp-bin.zip\n",
      StandardCharsets.UTF_8,
    )
    val emptyM2 = Files.createTempDirectory("maven-mbt-empty-m2")

    var recordedCommands: List[List[String]] = Nil
    val importer = new TestMavenMbtImporter(
      workspace,
      version = "2.0.0-RC1-22-d02f337b-SNAPSHOT",
      baseCommand = List("./mvnw"),
      env = Map("MAVEN_USER_HOME" -> emptyM2.toString),
      onRun = { (args, _) =>
        recordedCommands = recordedCommands :+ args
        Future.successful(1)
      },
    )

    val err = intercept[Exception] {
      Await.result(importer.extract(workspace), Duration.Inf)
    }
    assert(err.getMessage.contains("mbt-maven-wrapper-bootstrap"))
    assertEquals(recordedCommands, List(List("./mvnw", "--version")))
  }
}
