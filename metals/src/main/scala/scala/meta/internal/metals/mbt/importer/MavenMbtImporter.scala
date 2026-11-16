package scala.meta.internal.metals.mbt.importer

import java.io.File
import java.io.InputStream
import java.io.StringWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Properties
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal
import scala.util.matching.Regex

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.helpers.DefaultHandler

abstract class MavenMbtImporter(
    val projectRoot: AbsolutePath,
    shellRunner: ShellRunner,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext)
    extends MbtImportProvider {

  def mavenBaseCommand(): List[String]

  protected def pluginVersion: String = BuildInfo.metalsMavenPluginVersion

  protected def environment: Map[String, String] = sys.env

  override val name: String = "maven"

  private def pluginCoordinates: String =
    s"org.scalameta:metals-maven-plugin:$pluginVersion:export"

  protected def runExport(args: List[String]): Future[Int] =
    shellRunner
      .run(
        "maven-export",
        args,
        projectRoot,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
        additionalEnv = if (environment eq sys.env) Map.empty else environment,
      )
      .future

  protected def runWrapperBootstrap(baseArgs: List[String]): Future[Int] =
    if (shellRunner != null)
      shellRunner
        .run(
          "maven-wrapper-bootstrap",
          baseArgs :+ "--version",
          projectRoot,
          redirectErrorOutput = false,
          javaHome = userConfig().javaHome,
          additionalEnv =
            if (environment eq sys.env) Map.empty else environment,
        )
        .future
    else runExport(baseArgs :+ "--version")

  protected def createSnapshotGlobalSettings(baseArgs: List[String]): Path =
    MavenMbtImporter.writeSnapshotGlobalSettings(
      baseArgs,
      projectRoot,
      environment,
    )

  override def extract(workspace: AbsolutePath): Future[Unit] =
    Future
      .fromTry(Try {
        val out = outputPath(workspace)
        Files.createDirectories(out.toNIO.getParent)
        val baseArgs = mavenBaseCommand()
        val needsSettings = MavenMbtImporter.needsSnapshotGlobalSettings(
          pluginVersion,
          baseArgs,
          projectRoot,
        )
        (out, baseArgs, needsSettings)
      })
      .flatMap { case (out, baseArgs, needsSettings) =>
        val bootstrapFuture =
          if (
            needsSettings &&
            MavenMbtImporter.needsWrapperBootstrap(
              baseArgs,
              projectRoot,
              environment,
            )
          ) {
            val rawBootstrap =
              try runWrapperBootstrap(baseArgs)
              catch {
                case NonFatal(e) => Future.failed(e)
              }
            rawBootstrap.flatMap { code =>
              if (code == ExitCodes.Cancel)
                Future.failed(
                  new java.util.concurrent.CancellationException(
                    "mbt-maven-wrapper-bootstrap was cancelled"
                  )
                )
              else if (code == ExitCodes.Success)
                Future.unit
              else
                Future.failed(
                  new Exception(
                    s"mbt-maven-wrapper-bootstrap failed with exit code $code"
                  )
                )
            }
          } else Future.unit

        bootstrapFuture.flatMap { _ =>
          Future
            .fromTry(Try {
              val snapshotSettings =
                if (needsSettings)
                  Some(createSnapshotGlobalSettings(baseArgs))
                else None
              val settingsArgs = snapshotSettings match {
                case Some(settingsPath) =>
                  List("--global-settings", settingsPath.toString)
                case None => Nil
              }
              val args = baseArgs ::: settingsArgs ::: List(
                "--quiet",
                pluginCoordinates,
                s"-DmbtOutputFile=${out}",
              )
              (args, snapshotSettings)
            })
            .flatMap { case (args, snapshotSettings) =>
              val exportFuture =
                try runExport(args)
                catch {
                  case NonFatal(e) => Future.failed(e)
                }
              exportFuture
                .andThen { case _ =>
                  snapshotSettings
                    .foreach(path => Try(Files.deleteIfExists(path)))
                }
                .flatMap { code =>
                  if (code == ExitCodes.Cancel)
                    Future.failed(
                      new java.util.concurrent.CancellationException(
                        "mbt-maven-export was cancelled"
                      )
                    )
                  else if (code == ExitCodes.Success && Files.exists(out.toNIO))
                    Future.successful(())
                  else
                    Future.failed(
                      new Exception(
                        s"mbt-maven-export failed with exit code $code"
                      )
                    )
                }
            }
        }
      }

}

object MavenMbtImporter {

  val centralSnapshotsUrl: String =
    "https://central.sonatype.com/repository/maven-snapshots/"

  private val snapshotsProfileId: String = "metals-snapshots"

  private val wrapperScriptMvnRegex: Regex =
    """((?:[A-Za-z]:)?[^\s"':=]+[/\\]bin[/\\]mvn(?:\.cmd|\.bat)?)""".r

  val snapshotPluginSettingsXml: String =
    s"""|<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
        |          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        |          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
        |  <profiles>
        |    <profile>
        |      <id>$snapshotsProfileId</id>
        |      <pluginRepositories>
        |        <pluginRepository>
        |          <id>central-snapshots</id>
        |          <url>$centralSnapshotsUrl</url>
        |          <releases>
        |            <enabled>false</enabled>
        |          </releases>
        |          <snapshots>
        |            <enabled>true</enabled>
        |          </snapshots>
        |        </pluginRepository>
        |      </pluginRepositories>
        |    </profile>
        |  </profiles>
        |  <activeProfiles>
        |    <activeProfile>$snapshotsProfileId</activeProfile>
        |  </activeProfiles>
        |</settings>
        |""".stripMargin

  def needsSnapshotGlobalSettings(
      pluginVersion: String,
      baseArgs: List[String],
      projectRoot: AbsolutePath,
  ): Boolean =
    pluginVersion.endsWith("-SNAPSHOT") &&
      !baseArgs.exists(isGlobalSettingsArg) &&
      !hasProjectGlobalSettings(projectRoot)

  private[importer] def writeSnapshotGlobalSettings(
      baseArgs: List[String] = Nil,
      projectRoot: AbsolutePath = AbsolutePath(Paths.get(".")),
      env: Map[String, String] = sys.env,
  ): Path = {
    val content = buildSnapshotGlobalSettingsXml(baseArgs, projectRoot, env)
    val file = Files.createTempFile("metals-maven-snapshots-", ".xml")
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8)
      file
    } catch {
      case NonFatal(e) =>
        Try(Files.deleteIfExists(file))
        throw e
    }
  }

  def buildSnapshotGlobalSettingsXml(
      baseArgs: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String] = sys.env,
  ): String =
    resolveInstallationGlobalSettings(baseArgs, projectRoot, env) match {
      case Some(existing) => mergeSnapshotGlobalSettingsXml(existing)
      case None =>
        if (isMavenWrapperCommand(baseArgs)) {
          val allTokens = collectMavenTokens(baseArgs, projectRoot)
          resolveWrapperGlobalSettingsZip(
            baseArgs,
            allTokens,
            projectRoot,
            env,
          ) match {
            case Some((zipPath, entryName)) =>
              mergeSnapshotGlobalSettingsFromZip(zipPath, entryName)
            case None => snapshotPluginSettingsXml
          }
        } else snapshotPluginSettingsXml
    }

  def resolveInstallationGlobalSettings(
      baseArgs: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String] = sys.env,
  ): Option[Path] = {
    val allTokens = collectMavenTokens(baseArgs, projectRoot)

    val fromMavenConf =
      for {
        rawConf <- extractSystemProperty(allTokens, "maven.conf")
        confDir <- resolvePath(projectRoot, rawConf)
        settings = confDir.resolve("settings.xml")
        if Files.isRegularFile(settings)
      } yield settings

    if (isMavenWrapperCommand(baseArgs)) {
      fromMavenConf.orElse(
        resolveWrapperGlobalSettingsFile(baseArgs, allTokens, projectRoot, env)
      )
    } else {
      val fromMavenHomeProp =
        for {
          rawHome <- extractSystemProperty(allTokens, "maven.home")
          homeDir <- resolvePath(projectRoot, rawHome)
          settings = homeDir.resolve("conf").resolve("settings.xml")
          if Files.isRegularFile(settings)
        } yield settings

      fromMavenConf
        .orElse(fromMavenHomeProp)
        .orElse(
          baseArgs.headOption
            .flatMap(cmd => resolveExecutable(cmd, projectRoot, env))
            .flatMap(settingsFromExecutable)
        )
        .orElse(settingsFromEnvironment(projectRoot, env))
    }
  }

  private def collectMavenTokens(
      baseArgs: List[String],
      projectRoot: AbsolutePath,
  ): List[String] = {
    val mvnDir = projectRoot.resolve(".mvn")
    baseArgs ++
      configFileTokens(mvnDir.resolve("maven.config").toNIO) ++
      configFileTokens(mvnDir.resolve("jvm.config").toNIO)
  }

  private[importer] def mergeSnapshotGlobalSettingsXml(
      existingSettings: Path
  ): String =
    try {
      Using.resource(Files.newInputStream(existingSettings)) { in =>
        mergeSnapshotGlobalSettingsStream(in, existingSettings.toString)
      }
    } catch {
      case NonFatal(e) =>
        scribe.warn(
          s"Failed to merge global Maven settings from $existingSettings, using standalone snapshot settings",
          e,
        )
        snapshotPluginSettingsXml
    }

  private def mergeSnapshotGlobalSettingsFromZip(
      zipPath: Path,
      entryName: String,
  ): String =
    try {
      Using.resource(new ZipFile(zipPath.toFile)) { zip =>
        val entry = Option(zip.getEntry(entryName)).getOrElse(
          throw new IllegalArgumentException(
            s"Missing $entryName in $zipPath"
          )
        )
        Using.resource(zip.getInputStream(entry)) { in =>
          mergeSnapshotGlobalSettingsStream(in, s"$zipPath!$entryName")
        }
      }
    } catch {
      case NonFatal(e) =>
        scribe.warn(
          s"Failed to merge global Maven settings from $zipPath!$entryName, using standalone snapshot settings",
          e,
        )
        snapshotPluginSettingsXml
    }

  private def mergeSnapshotGlobalSettingsStream(
      in: InputStream,
      sourceDescription: String,
  ): String = {
    val dbf = DocumentBuilderFactory.newInstance()
    dbf.setNamespaceAware(true)
    dbf.setFeature(
      "http://apache.org/xml/features/disallow-doctype-decl",
      true,
    )
    List(
      "http://xml.org/sax/features/external-general-entities",
      "http://xml.org/sax/features/external-parameter-entities",
      "http://apache.org/xml/features/nonvalidating/load-external-dtd",
    ).foreach(dbf.setFeature(_, false))
    dbf.setXIncludeAware(false)
    dbf.setExpandEntityReferences(false)

    val builder = dbf.newDocumentBuilder()
    builder.setErrorHandler(new DefaultHandler())
    val doc = builder.parse(in)
    val root = Option(doc.getDocumentElement)
      .filter(el => elementName(el) == "settings")
      .getOrElse(
        throw new IllegalArgumentException(
          s"Expected <settings> root in $sourceDescription"
        )
      )
    val ns = Option(root.getNamespaceURI).filter(_.nonEmpty)
    val prefix = Option(root.getPrefix).filter(_.nonEmpty)

    val profiles = directChildElement(root, "profiles").getOrElse {
      val created = createElement(doc, ns, prefix, "profiles")
      root.appendChild(created)
      created
    }
    val hasProfile = directChildElements(profiles).exists { el =>
      elementName(el) == "profile" &&
      directChildElement(el, "id").exists(
        _.getTextContent.trim == snapshotsProfileId
      )
    }
    if (!hasProfile) {
      profiles.appendChild(createSnapshotProfileElement(doc, ns, prefix))
    }

    val activeProfiles =
      directChildElement(root, "activeProfiles").getOrElse {
        val created = createElement(doc, ns, prefix, "activeProfiles")
        root.appendChild(created)
        created
      }
    val isAlreadyActive = directChildElements(activeProfiles).exists { el =>
      elementName(el) == "activeProfile" &&
      el.getTextContent.trim == snapshotsProfileId
    }
    if (!isAlreadyActive) {
      activeProfiles.appendChild(
        createTextElement(
          doc,
          ns,
          prefix,
          "activeProfile",
          snapshotsProfileId,
        )
      )
    }

    val tf = TransformerFactory.newInstance()
    Try(tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""))
    Try(tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""))
    val transformer = tf.newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    val writer = new StringWriter()
    transformer.transform(new DOMSource(doc), new StreamResult(writer))
    writer.toString
  }

  private def createSnapshotProfileElement(
      doc: Document,
      ns: Option[String],
      prefix: Option[String],
  ): Element = {
    val profile = createElement(doc, ns, prefix, "profile")
    profile.appendChild(
      createTextElement(doc, ns, prefix, "id", snapshotsProfileId)
    )
    val pluginRepos = createElement(doc, ns, prefix, "pluginRepositories")
    val pluginRepo = createElement(doc, ns, prefix, "pluginRepository")
    pluginRepo.appendChild(
      createTextElement(doc, ns, prefix, "id", "central-snapshots")
    )
    pluginRepo.appendChild(
      createTextElement(doc, ns, prefix, "url", centralSnapshotsUrl)
    )
    val releases = createElement(doc, ns, prefix, "releases")
    releases.appendChild(createTextElement(doc, ns, prefix, "enabled", "false"))
    pluginRepo.appendChild(releases)
    val snapshots = createElement(doc, ns, prefix, "snapshots")
    snapshots.appendChild(createTextElement(doc, ns, prefix, "enabled", "true"))
    pluginRepo.appendChild(snapshots)
    pluginRepos.appendChild(pluginRepo)
    profile.appendChild(pluginRepos)
    profile
  }

  private def createElement(
      doc: Document,
      ns: Option[String],
      prefix: Option[String],
      name: String,
  ): Element =
    ns match {
      case Some(uri) =>
        val qName = prefix.map(p => s"$p:$name").getOrElse(name)
        doc.createElementNS(uri, qName)
      case None =>
        doc.createElement(name)
    }

  private def createTextElement(
      doc: Document,
      ns: Option[String],
      prefix: Option[String],
      name: String,
      value: String,
  ): Element = {
    val el = createElement(doc, ns, prefix, name)
    el.setTextContent(value)
    el
  }

  private def elementName(el: Element): String =
    Option(el.getLocalName).filter(_.nonEmpty).getOrElse(el.getTagName)

  private def directChildElements(parent: Element): IndexedSeq[Element] = {
    val children = parent.getChildNodes
    for {
      i <- 0 until children.getLength
      node = children.item(i)
      if node.getNodeType == Node.ELEMENT_NODE
    } yield node.asInstanceOf[Element]
  }

  private def directChildElement(
      parent: Element,
      name: String,
  ): Option[Element] =
    directChildElements(parent).find(el => elementName(el) == name)

  private def resolveExecutable(
      cmd: String,
      projectRoot: AbsolutePath,
      env: Map[String, String],
  ): Option[Path] = {
    val cleaned = stripQuotes(cmd.trim)
    if (cleaned.isEmpty) None
    else if (cleaned.contains("/") || cleaned.contains("\\")) {
      resolvePath(projectRoot, cleaned).flatMap(findExecutableCandidate)
    } else {
      val pathValue = env
        .collectFirst {
          case (k, v) if k.equalsIgnoreCase("PATH") => v
        }
        .getOrElse("")
      val candidates = for {
        rawDir <- pathValue.split(
          java.util.regex.Pattern.quote(File.pathSeparator)
        )
        trimmed = stripQuotes(rawDir.trim)
        if trimmed.nonEmpty
        dir <- Try(Paths.get(trimmed)).toOption
        exec <- findExecutableCandidate(dir.resolve(cleaned))
      } yield exec
      candidates.headOption
    }
  }

  private def findExecutableCandidate(base: Path): Option[Path] = {
    val fileName = Option(base.getFileName).map(_.toString).getOrElse("")
    if (fileName.isEmpty) None
    else {
      val lower = fileName.toLowerCase
      val names =
        if (
          Properties.isWin &&
          !lower.endsWith(".cmd") &&
          !lower.endsWith(".bat") &&
          !lower.endsWith(".exe")
        )
          List(s"$fileName.cmd", s"$fileName.bat", fileName)
        else List(fileName)
      val parent = Option(base.getParent)
      val resolved = names.map { n =>
        parent.map(_.resolve(n)).getOrElse(Paths.get(n))
      }
      resolved.find(Files.isRegularFile(_))
    }
  }

  private def settingsFromExecutable(execFile: Path): Option[Path] = {
    val realExec =
      Try(execFile.toRealPath()).getOrElse(execFile.toAbsolutePath.normalize())
    settingsFromBinExecutable(realExec).orElse {
      val isSmallFile =
        Try(Files.size(realExec)).toOption.exists(_ <= 16384L)
      if (!isSmallFile) None
      else {
        val text =
          Try(Files.readString(realExec, StandardCharsets.UTF_8)).getOrElse("")
        for {
          m <- wrapperScriptMvnRegex.findFirstMatchIn(text)
          target <- Try(Paths.get(m.group(1))).toOption
          if Files.isRegularFile(target)
          realTarget = Try(target.toRealPath())
            .getOrElse(target.toAbsolutePath.normalize())
          settings <- settingsFromBinExecutable(realTarget)
        } yield settings
      }
    }
  }

  private def settingsFromBinExecutable(exec: Path): Option[Path] =
    for {
      binDir <- Option(exec.getParent)
      binName <- Option(binDir.getFileName).map(_.toString)
      if binName.equalsIgnoreCase("bin")
      mavenHome <- Option(binDir.getParent)
      settings = mavenHome.resolve("conf").resolve("settings.xml")
      if Files.isRegularFile(settings)
    } yield settings

  private def settingsFromEnvironment(
      projectRoot: AbsolutePath,
      env: Map[String, String],
  ): Option[Path] = {
    val candidates = for {
      key <- List("MAVEN_HOME", "M2_HOME")
      raw <- env.get(key)
      trimmed = stripQuotes(raw.trim)
      if trimmed.nonEmpty
      home <- resolvePath(projectRoot, trimmed)
      settings = home.resolve("conf").resolve("settings.xml")
      if Files.isRegularFile(settings)
    } yield settings
    candidates.headOption
  }

  private final case class WrapperSpec(
      effectiveUri: URI,
      mavenUserHome: Path,
      distributionBase: String,
      distributionPath: String,
      zipStoreBase: String,
      zipStorePath: String,
      alwaysDownload: Boolean,
      alwaysUnpack: Boolean,
      mavenConfOverride: Option[Path],
  )

  def needsWrapperBootstrap(
      baseArgs: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String] = sys.env,
  ): Boolean =
    if (!isMavenWrapperCommand(baseArgs)) false
    else {
      val allTokens = collectMavenTokens(baseArgs, projectRoot)
      val hasExplicitMavenConf =
        (for {
          rawConf <- extractSystemProperty(allTokens, "maven.conf")
          confDir <- resolvePath(projectRoot, rawConf)
          settings = confDir.resolve("settings.xml")
          if Files.isRegularFile(settings)
        } yield settings).isDefined

      if (hasExplicitMavenConf) false
      else
        resolveWrapperSpec(baseArgs, allTokens, projectRoot, env) match {
          case None => false
          case Some(spec) =>
            val hasOverrideConf =
              spec.mavenConfOverride.exists(confDir =>
                Files.isRegularFile(confDir.resolve("settings.xml"))
              )
            if (hasOverrideConf) false
            else if (spec.alwaysDownload || spec.alwaysUnpack) true
            else !isWrapperDistributionCached(spec, projectRoot)
        }
    }

  private def isWrapperDistributionCached(
      spec: WrapperSpec,
      projectRoot: AbsolutePath,
  ): Boolean = {
    val hasUnpackedHome =
      (for {
        baseDir <- resolveWrapperBaseDir(
          spec.distributionBase,
          spec.mavenUserHome,
          projectRoot,
        )
        distsRoot = baseDir.resolve(spec.distributionPath)
        home <- findUnpackedWrapperHome(distsRoot, spec.effectiveUri)
      } yield home).isDefined

    hasUnpackedHome ||
    resolveWrapperZipCandidates(spec, projectRoot).exists(isReadableZipDist)
  }

  private def resolveWrapperGlobalSettingsFile(
      baseArgs: List[String],
      allTokens: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String],
  ): Option[Path] =
    resolveWrapperSpec(baseArgs, allTokens, projectRoot, env).flatMap { spec =>
      val fromWrapperMavenConf =
        for {
          confDir <- spec.mavenConfOverride
          settings = confDir.resolve("settings.xml")
          if Files.isRegularFile(settings)
        } yield settings

      fromWrapperMavenConf.orElse {
        for {
          baseDir <- resolveWrapperBaseDir(
            spec.distributionBase,
            spec.mavenUserHome,
            projectRoot,
          )
          distsRoot = baseDir.resolve(spec.distributionPath)
          home <- findUnpackedWrapperHome(distsRoot, spec.effectiveUri)
          settings = home.resolve("conf").resolve("settings.xml")
          if Files.isRegularFile(settings)
        } yield settings
      }
    }

  private def resolveWrapperZipCandidates(
      spec: WrapperSpec,
      projectRoot: AbsolutePath,
  ): List[Path] = {
    val uriPath = Option(spec.effectiveUri.getPath).getOrElse("")
    val archiveName =
      Option(Paths.get(uriPath).getFileName).map(_.toString).getOrElse("")
    val isZipArchive = archiveName.toLowerCase.endsWith(".zip")
    val distName = removeLastExtension(archiveName)
    val uriHash = Integer.toHexString(spec.effectiveUri.hashCode())
    val cachedZip =
      for {
        zipBaseDir <- resolveWrapperBaseDir(
          spec.zipStoreBase,
          spec.mavenUserHome,
          projectRoot,
        )
        if isZipArchive && distName.nonEmpty && archiveName.nonEmpty
      } yield zipBaseDir
        .resolve(spec.zipStorePath)
        .resolve(distName)
        .resolve(uriHash)
        .resolve(archiveName)

    val fileUriZip =
      if (isZipArchive && "file".equalsIgnoreCase(spec.effectiveUri.getScheme))
        Try(Paths.get(spec.effectiveUri)).toOption
      else None

    List(cachedZip, fileUriZip).flatten.distinct
  }

  private def resolveWrapperGlobalSettingsZip(
      baseArgs: List[String],
      allTokens: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String],
  ): Option[(Path, String)] =
    resolveWrapperSpec(baseArgs, allTokens, projectRoot, env).flatMap { spec =>
      val candidates = resolveWrapperZipCandidates(spec, projectRoot)
      val matches = for {
        zipPath <- candidates
        if Files.isRegularFile(zipPath)
        entryName <- findSettingsEntryInZip(zipPath)
      } yield (zipPath, entryName)
      matches.headOption
    }

  private def resolveWrapperSpec(
      baseArgs: List[String],
      allTokens: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String],
  ): Option[WrapperSpec] =
    for {
      propertiesFile <- findWrapperPropertiesFile(
        baseArgs,
        allTokens,
        projectRoot,
      )
      wrapperProps <- loadJavaProperties(propertiesFile)
      wrapperRootDir = Option(
        propertiesFile.toAbsolutePath.normalize().getParent
      )
        .flatMap(p => Option(p.getParent))
        .flatMap(p => Option(p.getParent))
      initialUserHome = resolveInitialMavenUserHome(
        allTokens,
        projectRoot,
        env,
      )
      extraSysProps = initialUserHome
        .map(h => readWrapperSystemProperties(h.resolve("maven.properties")))
        .getOrElse(Map.empty[String, String]) ++
        wrapperRootDir
          .map(r => readWrapperSystemProperties(r.resolve("maven.properties")))
          .getOrElse(Map.empty[String, String])
      rawDistUrl <- resolveWrapperProperty(
        "distributionUrl",
        allTokens,
        extraSysProps,
        wrapperProps,
      )
      effectiveUri <- computeWrapperDistributionUri(
        propertiesFile,
        rawDistUrl,
        allTokens,
        env,
      )
      mavenUserHome <- extractSystemProperty(allTokens, "maven.user.home")
        .orElse(extraSysProps.get("maven.user.home"))
        .flatMap(resolvePath(projectRoot, _))
        .orElse(initialUserHome)
    } yield {
      val mavenConfOverride =
        extraSysProps
          .get("maven.conf")
          .flatMap(resolvePath(projectRoot, _))
      WrapperSpec(
        effectiveUri = effectiveUri,
        mavenUserHome = mavenUserHome,
        distributionBase = resolveWrapperProperty(
          "distributionBase",
          allTokens,
          extraSysProps,
          wrapperProps,
        ).getOrElse("MAVEN_USER_HOME"),
        distributionPath = resolveWrapperProperty(
          "distributionPath",
          allTokens,
          extraSysProps,
          wrapperProps,
        ).getOrElse("wrapper/dists"),
        zipStoreBase = resolveWrapperProperty(
          "zipStoreBase",
          allTokens,
          extraSysProps,
          wrapperProps,
        ).getOrElse("MAVEN_USER_HOME"),
        zipStorePath = resolveWrapperProperty(
          "zipStorePath",
          allTokens,
          extraSysProps,
          wrapperProps,
        ).getOrElse("wrapper/dists"),
        alwaysDownload = resolveWrapperProperty(
          "alwaysDownload",
          allTokens,
          extraSysProps,
          wrapperProps,
        ).exists(_.equalsIgnoreCase("true")),
        alwaysUnpack = resolveWrapperProperty(
          "alwaysUnpack",
          allTokens,
          extraSysProps,
          wrapperProps,
        ).exists(_.equalsIgnoreCase("true")),
        mavenConfOverride = mavenConfOverride,
      )
    }

  private def resolveWrapperProperty(
      key: String,
      allTokens: List[String],
      extraSysProps: Map[String, String],
      wrapperProps: java.util.Properties,
  ): Option[String] =
    extractSystemProperty(allTokens, key)
      .orElse(extraSysProps.get(key))
      .orElse(Option(wrapperProps.getProperty(key)))
      .map(_.trim)
      .filter(_.nonEmpty)

  private def findWrapperPropertiesFile(
      baseArgs: List[String],
      allTokens: List[String],
      projectRoot: AbsolutePath,
  ): Option[Path] = {
    val fromClasspath =
      if (baseArgs.contains("org.apache.maven.wrapper.MavenWrapperMain")) {
        val cpEntries = extractClasspathEntries(baseArgs, projectRoot)
        val wrapperJars =
          cpEntries.filter { p =>
            Option(p.getFileName)
              .map(_.toString.toLowerCase)
              .exists(n => n.contains("maven-wrapper") && n.endsWith(".jar"))
          } ++ cpEntries.filter { p =>
            Option(p.getFileName)
              .map(_.toString.toLowerCase)
              .exists(_.endsWith(".jar"))
          }
        wrapperJars.distinct.flatMap { jar =>
          Option(jar.getFileName).map(_.toString).map { fileName =>
            jar.resolveSibling(
              fileName.replaceFirst("(?i)\\.jar$", ".properties")
            )
          }
        }
      } else Nil

    val fromScript =
      if (!baseArgs.contains("org.apache.maven.wrapper.MavenWrapperMain"))
        for {
          cmd <- baseArgs.headOption
          scriptPath <- resolvePath(projectRoot, cmd)
          scriptDir <- Option(
            scriptPath.toAbsolutePath.normalize().getParent
          )
        } yield scriptDir.resolve(".mvn/wrapper/maven-wrapper.properties")
      else None

    val fromMultiModule =
      extractSystemProperty(allTokens, "maven.multiModuleProjectDirectory")
        .flatMap(resolvePath(projectRoot, _))
        .map(_.resolve(".mvn/wrapper/maven-wrapper.properties"))

    val fromProjectRoot =
      projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties").toNIO

    (fromClasspath ++ List(
      fromScript,
      fromMultiModule,
      Some(fromProjectRoot),
    ).flatten).distinct
      .find(Files.isRegularFile(_))
  }

  private def extractClasspathEntries(
      baseArgs: List[String],
      projectRoot: AbsolutePath,
  ): List[Path] = {
    val sep = Regex.quote(File.pathSeparator)
    val (_, rawEntries) =
      baseArgs.foldLeft((false, List.empty[String])) {
        case ((true, acc), cp) =>
          (false, acc ++ cp.split(sep).filter(_.nonEmpty))
        case ((false, acc), "-cp" | "-classpath" | "--class-path") =>
          (true, acc)
        case ((false, acc), arg) if arg.startsWith("--class-path=") =>
          val cp = stripQuotes(arg.stripPrefix("--class-path="))
          (false, acc ++ cp.split(sep).filter(_.nonEmpty))
        case ((false, acc), _) =>
          (false, acc)
      }
    rawEntries.flatMap(resolvePath(projectRoot, _))
  }

  private def resolveInitialMavenUserHome(
      allTokens: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String],
  ): Option[Path] =
    extractSystemProperty(allTokens, "maven.user.home")
      .flatMap(resolvePath(projectRoot, _))
      .orElse(
        env
          .get("MAVEN_USER_HOME")
          .map(s => stripQuotes(s.trim))
          .filter(_.nonEmpty)
          .flatMap(resolvePath(projectRoot, _))
      )
      .orElse(
        extractSystemProperty(allTokens, "user.home")
          .flatMap(resolvePath(projectRoot, _))
          .map(_.resolve(".m2"))
      )
      .orElse(
        env
          .get("HOME")
          .orElse(env.get("USERPROFILE"))
          .map(s => stripQuotes(s.trim))
          .filter(_.nonEmpty)
          .flatMap(resolvePath(projectRoot, _))
          .map(_.resolve(".m2"))
      )
      .orElse(
        if (env == sys.env)
          Option(System.getProperty("user.home"))
            .filter(_.nonEmpty)
            .map(Paths.get(_, ".m2"))
        else None
      )

  private def computeWrapperDistributionUri(
      propertiesFile: Path,
      rawDistUrl: String,
      allTokens: List[String],
      env: Map[String, String],
  ): Option[URI] =
    Try {
      val rawUri = new URI(rawDistUrl)
      if (rawUri.getScheme == null) {
        Option(propertiesFile.toAbsolutePath.normalize().getParent)
          .map(_.resolve(rawUri.getSchemeSpecificPart).normalize().toUri)
          .getOrElse(rawUri)
      } else {
        val repoUrlOpt =
          extractSystemProperty(allTokens, "MVNW_REPOURL")
            .orElse(env.get("MVNW_REPOURL"))
            .map(s => stripQuotes(s.trim).stripSuffix("/"))
            .filter(_.nonEmpty)
        repoUrlOpt match {
          case Some(repoUrl) =>
            val rawPath = Option(rawUri.getPath).getOrElse("")
            val mvndIdx = rawPath.indexOf("maven/mvnd")
            val mavenIdx = rawPath.indexOf("org/apache/maven")
            val idx = if (mvndIdx > 1) mvndIdx else mavenIdx
            val suffix =
              if (idx > 1) "/" + rawPath.substring(idx)
              else if (rawPath.startsWith("/")) rawPath
              else "/" + rawPath
            new URI(repoUrl + suffix)
          case None => rawUri
        }
      }
    }.toOption

  private def resolveWrapperBaseDir(
      baseName: String,
      mavenUserHome: Path,
      projectRoot: AbsolutePath,
  ): Option[Path] =
    baseName match {
      case "MAVEN_USER_HOME" => Some(mavenUserHome)
      case "PROJECT" => Some(projectRoot.toNIO)
      case _ => None
    }

  private def findUnpackedWrapperHome(
      distsRoot: Path,
      effectiveUri: URI,
  ): Option[Path] = {
    val uriPath = Option(effectiveUri.getPath).getOrElse("")
    val archiveName =
      Option(Paths.get(uriPath).getFileName).map(_.toString).getOrElse("")
    val withoutLastExt = removeLastExtension(archiveName)
    val withoutTarGz = archiveName.stripSuffix(".tar.gz")
    val distNames = List(
      withoutLastExt,
      withoutTarGz,
      withoutLastExt.stripSuffix("-bin"),
      withoutTarGz.stripSuffix("-bin"),
    ).filter(_.nonEmpty).distinct

    val hashes = List(
      Integer.toHexString(effectiveUri.hashCode()),
      Integer.toHexString(effectiveUri.toString.hashCode()),
      md5Hex(effectiveUri.toString),
    ).distinct

    val candidates = for {
      distName <- distNames
      hash <- hashes
      hashDir = distsRoot.resolve(distName).resolve(hash)
      home <- wrapperHomeInHashDir(hashDir)
    } yield home
    candidates.headOption
  }

  private def isUnpackedMavenHome(dir: Path): Boolean =
    Files.isDirectory(dir.resolve("bin")) ||
      Files.isDirectory(dir.resolve("boot")) ||
      Files.isRegularFile(dir.resolve("conf").resolve("settings.xml"))

  private def wrapperHomeInHashDir(hashDir: Path): Option[Path] =
    if (!Files.isDirectory(hashDir)) None
    else if (isUnpackedMavenHome(hashDir)) Some(hashDir)
    else {
      val subdirs = Try {
        Using.resource(Files.list(hashDir)) { stream =>
          stream
            .toArray(n => new Array[Path](n))
            .filter(Files.isDirectory(_))
        }
      }.getOrElse(Array.empty[Path])
      if (subdirs.length == 1 && isUnpackedMavenHome(subdirs(0)))
        Some(subdirs(0))
      else None
    }

  private def isReadableZipDist(zipPath: Path): Boolean =
    Files.isRegularFile(zipPath) &&
      Try {
        Using.resource(new ZipFile(zipPath.toFile)) { zip =>
          val entries = zip.stream().toArray(n => new Array[ZipEntry](n))
          zipSingleTopDir(entries).isDefined
        }
      }.getOrElse(false)

  private def zipSingleTopDir(entries: Array[ZipEntry]): Option[String] = {
    val topDirs = entries.flatMap { entry =>
      val normalized = entry.getName.stripPrefix("/")
      val slash = normalized.indexOf('/')
      if (slash > 0) Some(normalized.substring(0, slash))
      else if (entry.isDirectory && normalized.nonEmpty) Some(normalized)
      else None
    }.distinct
    if (topDirs.length == 1) Some(topDirs(0)) else None
  }

  private def findSettingsEntryInZip(zipPath: Path): Option[String] =
    Try {
      Using.resource(new ZipFile(zipPath.toFile)) { zip =>
        val entries = zip.stream().toArray(n => new Array[ZipEntry](n))
        for {
          topDir <- zipSingleTopDir(entries)
          entry <- Option(zip.getEntry(s"$topDir/conf/settings.xml"))
          if !entry.isDirectory
        } yield entry.getName
      }
    }.toOption.flatten

  private def loadJavaProperties(file: Path): Option[java.util.Properties] =
    if (!Files.isRegularFile(file)) None
    else
      Try {
        val props = new java.util.Properties()
        Using.resource(Files.newInputStream(file))(props.load)
        props
      }.toOption

  private def readWrapperSystemProperties(file: Path): Map[String, String] =
    loadJavaProperties(file) match {
      case None => Map.empty
      case Some(props) =>
        val prefix = "systemProp."
        val keys = props.stringPropertyNames().toArray(new Array[String](0))
        (for {
          key <- keys
          if key.startsWith(prefix)
          propName = key.stripPrefix(prefix)
          if propName.nonEmpty
          value <- Option(props.getProperty(key))
        } yield propName -> value).toMap
    }

  private def removeLastExtension(name: String): String = {
    val dot = name.lastIndexOf('.')
    if (dot > 0) name.substring(0, dot) else name
  }

  private def md5Hex(input: String): String = {
    val digest = MessageDigest
      .getInstance("MD5")
      .digest(input.getBytes(StandardCharsets.UTF_8))
    digest.map(b => f"${b & 0xff}%02x").mkString
  }

  private def extractSystemProperty(
      tokens: Seq[String],
      key: String,
  ): Option[String] = {
    val inlinePrefix = s"-D$key="
    val separatePrefix = s"$key="
    val (_, found) =
      tokens.foldLeft((false, Option.empty[String])) {
        case ((true, prev), token) =>
          val next =
            if (token.startsWith(separatePrefix))
              Some(stripQuotes(token.stripPrefix(separatePrefix)))
            else prev
          (false, next)
        case ((false, prev), "-D") =>
          (true, prev)
        case ((false, _), token) if token.startsWith(inlinePrefix) =>
          (false, Some(stripQuotes(token.stripPrefix(inlinePrefix))))
        case ((false, prev), _) =>
          (false, prev)
      }
    found
  }

  private def resolvePath(
      projectRoot: AbsolutePath,
      raw: String,
  ): Option[Path] =
    Try(Paths.get(stripQuotes(raw))).toOption.map { p =>
      if (p.isAbsolute) p else projectRoot.toNIO.resolve(p)
    }

  private def stripQuotes(value: String): String =
    value.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")

  private def isMavenWrapperCommand(baseArgs: List[String]): Boolean =
    baseArgs.contains("org.apache.maven.wrapper.MavenWrapperMain") ||
      baseArgs.headOption.exists { cmd =>
        val normalized = stripQuotes(cmd).replace('\\', '/')
        val name = normalized.substring(normalized.lastIndexOf('/') + 1)
        name.equalsIgnoreCase("mvnw") || name.equalsIgnoreCase("mvnw.cmd")
      }

  private def isGlobalSettingsArg(arg: String): Boolean =
    arg == "-gs" ||
      arg.startsWith("-gs=") ||
      arg == "--global-settings" ||
      arg.startsWith("--global-settings=")

  private def hasProjectGlobalSettings(projectRoot: AbsolutePath): Boolean =
    configFileTokens(projectRoot.resolve(".mvn/maven.config").toNIO)
      .exists(isGlobalSettingsArg)

  private def configFileTokens(file: Path): Array[String] =
    if (!Files.isRegularFile(file)) Array.empty[String]
    else
      for {
        rawLine <- Try(
          Files.readString(file, StandardCharsets.UTF_8).split("\\R")
        ).getOrElse(Array.empty[String])
        line = rawLine.trim
        if line.nonEmpty && !line.startsWith("#")
        token <- line.split("\\s+")
      } yield token

}
