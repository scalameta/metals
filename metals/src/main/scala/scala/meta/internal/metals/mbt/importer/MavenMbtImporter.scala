package scala.meta.internal.metals.mbt.importer

import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
      )
      .future

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
        val snapshotSettings =
          if (
            MavenMbtImporter.needsSnapshotGlobalSettings(
              pluginVersion,
              baseArgs,
              projectRoot,
            )
          )
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
        (out, args, snapshotSettings)
      })
      .flatMap { case (out, args, snapshotSettings) =>
        val exportFuture =
          try runExport(args)
          catch {
            case NonFatal(e) => Future.failed(e)
          }
        exportFuture
          .andThen { case _ =>
            snapshotSettings.foreach(path => Try(Files.deleteIfExists(path)))
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
      case None => snapshotPluginSettingsXml
    }

  def resolveInstallationGlobalSettings(
      baseArgs: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String] = sys.env,
  ): Option[Path] = {
    val mvnDir = projectRoot.resolve(".mvn")
    val allTokens =
      baseArgs ++
        configFileTokens(mvnDir.resolve("maven.config").toNIO) ++
        configFileTokens(mvnDir.resolve("jvm.config").toNIO)

    val fromMavenConf =
      for {
        rawConf <- extractSystemProperty(allTokens, "maven.conf")
        confDir <- resolvePath(projectRoot, rawConf)
        settings = confDir.resolve("settings.xml")
        if Files.isRegularFile(settings)
      } yield settings

    val fromMavenHomeProp =
      for {
        rawHome <- extractSystemProperty(allTokens, "maven.home")
        homeDir <- resolvePath(projectRoot, rawHome)
        settings = homeDir.resolve("conf").resolve("settings.xml")
        if Files.isRegularFile(settings)
      } yield settings

    fromMavenConf
      .orElse(fromMavenHomeProp)
      .orElse {
        if (isMavenWrapperCommand(baseArgs)) None
        else
          baseArgs.headOption
            .flatMap(cmd => resolveExecutable(cmd, projectRoot, env))
            .flatMap(settingsFromExecutable)
            .orElse(settingsFromEnvironment(projectRoot, env))
      }
  }

  private[importer] def mergeSnapshotGlobalSettingsXml(
      existingSettings: Path
  ): String =
    try {
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

      val doc = Using.resource(Files.newInputStream(existingSettings)) { in =>
        val builder = dbf.newDocumentBuilder()
        builder.setErrorHandler(new DefaultHandler())
        builder.parse(in)
      }
      val root = Option(doc.getDocumentElement)
        .filter(el => elementName(el) == "settings")
        .getOrElse(
          throw new IllegalArgumentException(
            s"Expected <settings> root in $existingSettings"
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
    } catch {
      case NonFatal(e) =>
        scribe.warn(
          s"Failed to merge global Maven settings from $existingSettings, using standalone snapshot settings",
          e,
        )
        snapshotPluginSettingsXml
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

  private def extractSystemProperty(
      tokens: Seq[String],
      key: String,
  ): Option[String] = {
    val inlinePrefix = s"-D$key="
    val separatePrefix = s"$key="
    val fromInline = tokens.collectFirst {
      case token if token.startsWith(inlinePrefix) =>
        stripQuotes(token.stripPrefix(inlinePrefix))
    }
    fromInline.orElse {
      tokens
        .zip(tokens.drop(1))
        .collectFirst {
          case ("-D", kv) if kv.startsWith(separatePrefix) =>
            stripQuotes(kv.stripPrefix(separatePrefix))
        }
    }
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
        val name = Try(
          Option(Paths.get(stripQuotes(cmd)).getFileName)
            .map(_.toString)
            .getOrElse(cmd)
        ).getOrElse(cmd)
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
