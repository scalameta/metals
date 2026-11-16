package scala.meta.internal.metals.mbt.importer

import java.io.FileNotFoundException
import java.io.InputStream
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
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

  protected def defaultUserHome: String = System.getProperty("user.home")

  override val name: String = "maven"

  private def pluginCoordinates: String =
    s"org.scalameta:metals-maven-plugin:$pluginVersion:export"

  protected def runExport(
      args: List[String],
      envOverrides: Map[String, String] = Map.empty,
  ): Future[Int] = {
    val baseEnv: Map[String, String] =
      if (environment eq sys.env) Map.empty else environment
    shellRunner
      .run(
        "maven-export",
        args,
        projectRoot,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
        additionalEnv = baseEnv ++ envOverrides,
      )
      .future
  }

  protected def createSnapshotSettings(
      baseArgs: List[String]
  ): MavenMbtImporter.SnapshotSettingsPlan =
    MavenMbtImporter.writeSnapshotUserSettings(
      baseArgs,
      projectRoot,
      environment,
      defaultUserHome,
    )

  override def extract(workspace: AbsolutePath): Future[Unit] = {
    var createdSettings: Option[Path] = None
    Future
      .fromTry(
        Try {
          val out = outputPath(workspace)
          Files.createDirectories(out.toNIO.getParent)
          val baseArgs = mavenBaseCommand()
          val (effectiveBaseArgs, envOverrides) =
            if (MavenMbtImporter.isSnapshotPlugin(pluginVersion)) {
              val plan = createSnapshotSettings(baseArgs)
              createdSettings = Some(plan.settingsFile)
              (plan.effectiveBaseArgs, plan.envOverrides)
            } else (baseArgs, Map.empty[String, String])
          val args = MavenMbtImporter.buildExportCommand(
            effectiveBaseArgs,
            createdSettings,
            pluginCoordinates,
            out,
          )
          (out, args, createdSettings, envOverrides)
        }.recoverWith { case NonFatal(e) =>
          createdSettings.foreach(path => Try(Files.deleteIfExists(path)))
          scala.util.Failure(e)
        }
      )
      .flatMap { case (out, args, snapshotSettings, envOverrides) =>
        val exportFuture =
          try runExport(args, envOverrides)
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

}

object MavenMbtImporter {

  val centralSnapshotsUrl: String =
    "https://central.sonatype.com/repository/maven-snapshots/"

  private val snapshotsProfileId: String = "metals-snapshots"

  private val mavenWrapperMainClass: String =
    "org.apache.maven.wrapper.MavenWrapperMain"

  private val tokenRegex: Regex = """"([^"]*)"|'([^']*)'|(\S+)""".r

  private val optionsTakingArgument: Set[String] =
    "-f --file -D --define -P --activate-profiles -gs --global-settings -t --toolchains -gt --global-toolchains -rf --resume-from -pl --projects -l --log-file -T --threads -b --builder"
      .split(' ')
      .toSet

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

  def isSnapshotPlugin(pluginVersion: String): Boolean =
    pluginVersion.endsWith("-SNAPSHOT")

  def buildExportCommand(
      effectiveBaseArgs: List[String],
      snapshotSettings: Option[Path],
      pluginCoordinates: String,
      out: AbsolutePath,
  ): List[String] = {
    val exportTail = List(
      "--quiet",
      pluginCoordinates,
      s"-DmbtOutputFile=${out}",
    )
    snapshotSettings match {
      case None => effectiveBaseArgs ::: exportTail
      case Some(settingsPath) =>
        val settingsArgs = List("--settings", settingsPath.toString)
        val (launcherPrefix, _, mavenCliArgs, _) = splitCommandArgs(
          effectiveBaseArgs
        )
        val dashDashIdx = mavenCliArgs.indexOf("--")
        if (dashDashIdx >= 0) {
          val (beforeDashDash, fromDashDash) =
            mavenCliArgs.splitAt(dashDashIdx)
          launcherPrefix ::: beforeDashDash ::: settingsArgs ::: exportTail ::: fromDashDash
        } else {
          effectiveBaseArgs ::: settingsArgs ::: exportTail
        }
    }
  }

  final case class SnapshotSettingsPlan(
      effectiveBaseArgs: List[String],
      settingsFile: Path,
      envOverrides: Map[String, String] = Map.empty,
  )

  /**
   * Writes a temporary Maven `--settings` file enabling the Central Portal
   * Snapshots `<pluginRepository>` while preserving any existing user settings
   * (explicit `-s` / `--settings` in `MAVEN_ARGS`, `baseArgs`, or
   * `.mvn/maven.config`, or default `${user.home}/.m2/settings.xml`).
   *
   * Passing `--settings` rather than `--global-settings` lets Maven natively
   * load `${maven.conf}/settings.xml` (for installed `mvn`, `./mvnw`, and
   * `MavenWrapperMain` distributions, cached or uncached) and merge user
   * settings on top via `DefaultSettingsBuilder`.
   */
  def writeSnapshotUserSettings(
      baseArgs: List[String] = Nil,
      projectRoot: AbsolutePath = AbsolutePath(Paths.get(".")),
      env: Map[String, String] = sys.env,
      userHome: String = System.getProperty("user.home"),
  ): SnapshotSettingsPlan = {
    val (strippedBaseArgs, envOverrides, xml) =
      prepareSnapshotUserSettingsXml(baseArgs, projectRoot, env, userHome)
    val tempFile = createSecureTempSettingsFile()
    try {
      Files.writeString(tempFile, xml, StandardCharsets.UTF_8)
      SnapshotSettingsPlan(strippedBaseArgs, tempFile, envOverrides)
    } catch {
      case NonFatal(e) =>
        Try(Files.deleteIfExists(tempFile))
        throw e
    }
  }

  def buildSnapshotUserSettingsXml(
      baseArgs: List[String] = Nil,
      projectRoot: AbsolutePath = AbsolutePath(Paths.get(".")),
      env: Map[String, String] = sys.env,
      userHome: String = System.getProperty("user.home"),
  ): String =
    prepareSnapshotUserSettingsXml(baseArgs, projectRoot, env, userHome)._3

  private def prepareSnapshotUserSettingsXml(
      baseArgs: List[String],
      projectRoot: AbsolutePath,
      env: Map[String, String],
      userHome: String,
  ): (List[String], Map[String, String], String) = {
    val (launcherPrefix, jvmArgs, mavenCliArgs, isJavaWrapper) =
      splitCommandArgs(baseArgs)
    val mavenArgsTokens =
      if (isJavaWrapper) Nil
      else env.get("MAVEN_ARGS").map(tokenizeLine).getOrElse(Nil)
    val foldMavenArgsIntoCli = mavenArgsTokens.exists(t =>
      t == "--" || t == "-s" || t == "--settings" ||
        t.startsWith("--settings=") || (t.startsWith("-s") && t.length > 2)
    )
    val (strippedMavenCliArgs, cliSettingsOpt, envOverrides) =
      if (foldMavenArgsIntoCli) {
        val (stripped, settingsOpt) =
          stripAndExtractSettingsOption(mavenArgsTokens ::: mavenCliArgs)
        (stripped, settingsOpt, Map("MAVEN_ARGS" -> ""))
      } else {
        val (stripped, settingsOpt) =
          stripAndExtractSettingsOption(mavenCliArgs)
        (stripped, settingsOpt, Map.empty[String, String])
      }
    val definesCliArgs =
      if (foldMavenArgsIntoCli) strippedMavenCliArgs
      else mavenArgsTokens ::: strippedMavenCliArgs

    val multiModuleDir =
      resolveMultiModuleDir(jvmArgs, isJavaWrapper, projectRoot, env)
    val mavenConfigTokens =
      readMavenConfigTokens(multiModuleDir.resolve(".mvn/maven.config"))
    val effectiveUserHome = resolveEffectiveUserHome(
      jvmArgs = jvmArgs,
      mavenCliArgs = definesCliArgs,
      mavenConfigTokens = mavenConfigTokens,
      multiModuleDir = multiModuleDir,
      isJavaWrapper = isJavaWrapper,
      env = env,
      defaultUserHome = userHome,
    )

    val explicitSettingsRaw = cliSettingsOpt.orElse {
      val (_, cfgSettingsOpt) = stripAndExtractSettingsOption(mavenConfigTokens)
      cfgSettingsOpt
    }

    val baseSettingsFile: Option[Path] = explicitSettingsRaw match {
      case Some(rawPath) =>
        val resolved = resolveAgainstWorkingDir(projectRoot, rawPath)
        if (!Files.isRegularFile(resolved))
          throw new FileNotFoundException(
            s"The specified user settings file does not exist: $resolved"
          )
        Some(resolved)
      case None =>
        val candidate =
          Paths.get(effectiveUserHome).resolve(".m2").resolve("settings.xml")
        if (Files.isRegularFile(candidate)) Some(candidate) else None
    }

    val xml = baseSettingsFile match {
      case Some(file) => mergeSnapshotSettingsFile(file)
      case None => snapshotPluginSettingsXml
    }
    (launcherPrefix ::: strippedMavenCliArgs, envOverrides, xml)
  }

  private def createSecureTempSettingsFile(): Path = {
    val isPosix =
      FileSystems.getDefault.supportedFileAttributeViews().contains("posix")
    val path =
      if (isPosix) {
        val perms = PosixFilePermissions.fromString("rw-------")
        Files.createTempFile(
          "metals-maven-settings-",
          ".xml",
          PosixFilePermissions.asFileAttribute(perms),
        )
      } else {
        Files.createTempFile("metals-maven-settings-", ".xml")
      }
    path.toFile.deleteOnExit()
    path
  }

  private def splitCommandArgs(
      baseArgs: List[String]
  ): (List[String], List[String], List[String], Boolean) = {
    val wrapperIdx = baseArgs.indexOf(mavenWrapperMainClass)
    if (wrapperIdx >= 0) {
      val launcherPrefix = baseArgs.take(wrapperIdx + 1)
      val jvmArgs = baseArgs.slice(1, wrapperIdx)
      val mavenCliArgs = baseArgs.drop(wrapperIdx + 1)
      (launcherPrefix, jvmArgs, mavenCliArgs, true)
    } else {
      (baseArgs.take(1), Nil, baseArgs.drop(1), false)
    }
  }

  private def stripAndExtractSettingsOption(
      args: List[String]
  ): (List[String], Option[String]) = {
    @tailrec
    def loop(
        remaining: List[String],
        keptRev: List[String],
        firstSettings: Option[String],
    ): (List[String], Option[String]) =
      remaining match {
        case Nil => (keptRev.reverse, firstSettings)
        case "--" :: _ => (keptRev.reverse ::: remaining, firstSettings)
        case ("-s" | "--settings") :: value :: rest =>
          loop(
            rest,
            keptRev,
            firstSettings.orElse(Some(stripQuotes(value))),
          )
        case (opt @ ("-s" | "--settings")) :: Nil =>
          throw new IllegalArgumentException(
            s"Missing argument for option: $opt"
          )
        case arg :: rest if arg.startsWith("--settings=") =>
          val raw = stripQuotes(arg.stripPrefix("--settings="))
          loop(rest, keptRev, firstSettings.orElse(Some(raw)))
        case arg :: rest if arg.startsWith("-s") && arg.length > 2 =>
          val afterOpt = arg.substring(2)
          val raw = stripQuotes(
            if (afterOpt.startsWith("=")) afterOpt.substring(1) else afterOpt
          )
          loop(rest, keptRev, firstSettings.orElse(Some(raw)))
        case opt :: value :: rest if optionsTakingArgument.contains(opt) =>
          loop(rest, value :: opt :: keptRev, firstSettings)
        case arg :: rest =>
          loop(rest, arg :: keptRev, firstSettings)
      }

    loop(args, Nil, None)
  }

  private def resolveMultiModuleDir(
      jvmArgs: List[String],
      isJavaWrapper: Boolean,
      projectRoot: AbsolutePath,
      env: Map[String, String],
  ): Path = {
    val jvmSources =
      if (isJavaWrapper) jvmArgs
      else env.get("MAVEN_OPTS").map(tokenizeLine).getOrElse(Nil)
    extractLastJvmSystemProperty(
      jvmSources,
      "maven.multiModuleProjectDirectory",
    ).orElse(
      if (isJavaWrapper) None
      else env.get("MAVEN_PROJECTBASEDIR").filter(_.nonEmpty)
    ).map(resolveAgainstWorkingDir(projectRoot, _))
      .getOrElse(projectRoot.toNIO)
  }

  private def resolveEffectiveUserHome(
      jvmArgs: List[String],
      mavenCliArgs: List[String],
      mavenConfigTokens: List[String],
      multiModuleDir: Path,
      isJavaWrapper: Boolean,
      env: Map[String, String],
      defaultUserHome: String,
  ): String = {
    val jvmSources =
      if (isJavaWrapper) jvmArgs
      else {
        val jvmConfigTokens =
          readJvmConfigTokens(multiModuleDir.resolve(".mvn/jvm.config"))
        val mavenOptsTokens =
          env.get("MAVEN_OPTS").map(tokenizeLine).getOrElse(Nil)
        jvmConfigTokens ::: mavenOptsTokens
      }
    val launcherUserHome =
      extractLastJvmSystemProperty(jvmSources, "user.home")
        .getOrElse(defaultUserHome)
    extractMavenDefines(mavenConfigTokens ::: mavenCliArgs)
      .getOrElse("user.home", launcherUserHome)
  }

  private def resolveAgainstWorkingDir(
      projectRoot: AbsolutePath,
      rawPath: String,
  ): Path = {
    val candidate = Paths.get(stripQuotes(rawPath))
    if (candidate.isAbsolute) candidate.normalize()
    else projectRoot.toNIO.resolve(candidate).normalize()
  }

  private def readMavenConfigTokens(file: Path): List[String] =
    if (!Files.isRegularFile(file)) Nil
    else {
      val lines = Try(
        Files.readString(file, StandardCharsets.UTF_8).split("\\r?\\n", -1)
      ).getOrElse(Array.empty[String])

      @tailrec
      def parseLines(
          idx: Int,
          expectingArg: Boolean,
          accRev: List[String],
      ): List[String] =
        if (idx >= lines.length) accRev.reverse
        else {
          val trimmed = lines(idx).trim
          if (trimmed.isEmpty || trimmed.startsWith("#")) {
            parseLines(idx + 1, expectingArg, accRev)
          } else if (expectingArg && !trimmed.startsWith("-")) {
            parseLines(idx + 1, false, stripQuotes(trimmed) :: accRev)
          } else if (
            trimmed.startsWith("--settings=") ||
            (trimmed.startsWith("-s") &&
              trimmed.length > 2 &&
              !trimmed.charAt(2).isWhitespace)
          ) {
            parseLines(idx + 1, false, trimmed :: accRev)
          } else {
            val lineTokens = tokenizeLine(trimmed)
            val nextExpecting = lineTokens.lastOption.exists(t =>
              t == "-s" || t == "--settings" || optionsTakingArgument.contains(
                t
              )
            ) && (lineTokens.length == 1 || !optionsTakingArgument.contains(
              lineTokens(lineTokens.length - 2)
            ))
            parseLines(idx + 1, nextExpecting, lineTokens.reverse ::: accRev)
          }
        }

      parseLines(0, expectingArg = false, Nil)
    }

  private def readJvmConfigTokens(file: Path): List[String] =
    if (!Files.isRegularFile(file)) Nil
    else {
      val lines = Try(
        Files.readString(file, StandardCharsets.UTF_8).split("\\r?\\n", -1)
      ).getOrElse(Array.empty[String])
      lines.foldLeft(List.empty[String]) { (acc, rawLine) =>
        val trimmed = rawLine.trim
        if (trimmed.isEmpty || trimmed.startsWith("#")) acc
        else acc ::: tokenizeLine(trimmed)
      }
    }

  private def tokenizeLine(line: String): List[String] =
    tokenRegex
      .findAllMatchIn(line)
      .foldLeft(List.empty[String]) { (acc, m) =>
        val token = Option(m.group(1))
          .orElse(Option(m.group(2)))
          .getOrElse(m.group(3))
        token :: acc
      }
      .reverse

  private def extractMavenDefines(args: List[String]): Map[String, String] = {
    def parseKeyValue(raw: String): (String, String) = {
      val cleaned = stripQuotes(raw)
      val eqIdx = cleaned.indexOf('=')
      if (eqIdx > 0)
        (cleaned.substring(0, eqIdx).trim, cleaned.substring(eqIdx + 1))
      else (cleaned.trim, "true")
    }

    @tailrec
    def loop(
        remaining: List[String],
        acc: Map[String, String],
    ): Map[String, String] =
      remaining match {
        case Nil | "--" :: _ => acc
        case ("-D" | "--define") :: value :: rest =>
          val (k, v) = parseKeyValue(value)
          loop(rest, if (k.nonEmpty) acc.updated(k, v) else acc)
        case arg :: rest if arg.startsWith("--define=") =>
          val (k, v) = parseKeyValue(arg.stripPrefix("--define="))
          loop(rest, if (k.nonEmpty) acc.updated(k, v) else acc)
        case arg :: rest if arg.startsWith("-D") && arg.length > 2 =>
          val (k, v) = parseKeyValue(arg.substring(2))
          loop(rest, if (k.nonEmpty) acc.updated(k, v) else acc)
        case ("-s" | "--settings") :: _ :: rest =>
          loop(rest, acc)
        case opt :: _ :: rest if optionsTakingArgument.contains(opt) =>
          loop(rest, acc)
        case _ :: rest =>
          loop(rest, acc)
      }

    loop(args, Map.empty)
  }

  private def extractLastJvmSystemProperty(
      jvmArgs: List[String],
      key: String,
  ): Option[String] = {
    val exactPrefix = s"-D$key="
    val bareFlag = s"-D$key"
    jvmArgs.foldLeft(Option.empty[String]) { (acc, token) =>
      val cleaned =
        if (token.startsWith("-D") && token.length > 2)
          "-D" + stripQuotes(token.substring(2))
        else token
      if (cleaned.startsWith(exactPrefix))
        Some(cleaned.substring(exactPrefix.length))
      else if (cleaned == bareFlag) Some("")
      else acc
    }
  }

  private def stripQuotes(value: String): String =
    if (
      value.length >= 2 &&
      ((value.startsWith("\"") && value.endsWith("\"")) ||
        (value.startsWith("'") && value.endsWith("'")))
    )
      value.substring(1, value.length - 1)
    else value

  private def mergeSnapshotSettingsFile(settingsFile: Path): String =
    try {
      Using.resource(Files.newInputStream(settingsFile)) { in =>
        mergeSnapshotSettingsStream(in, settingsFile.toString)
      }
    } catch {
      case NonFatal(e) =>
        scribe.warn(
          s"Failed to merge Maven user settings from $settingsFile, using standalone snapshot settings",
          e,
        )
        snapshotPluginSettingsXml
    }

  private def mergeSnapshotSettingsStream(
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
    val existingProfileIds = (for {
      el <- directChildElements(profiles)
      if elementName(el) == "profile"
      idEl <- directChildElement(el, "id")
    } yield idEl.getTextContent.trim).toSet

    val profileId = uniqueProfileId(existingProfileIds)
    profiles.appendChild(
      createSnapshotProfileElement(doc, ns, prefix, profileId)
    )

    val activeProfiles =
      directChildElement(root, "activeProfiles").getOrElse {
        val created = createElement(doc, ns, prefix, "activeProfiles")
        root.appendChild(created)
        created
      }
    activeProfiles.appendChild(
      createTextElement(doc, ns, prefix, "activeProfile", profileId)
    )

    val tf = TransformerFactory.newInstance()
    Try(tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""))
    Try(tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""))
    val transformer = tf.newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    val writer = new StringWriter()
    transformer.transform(new DOMSource(doc), new StreamResult(writer))
    writer.toString
  }

  @tailrec
  private def uniqueProfileId(
      existingIds: Set[String],
      suffix: Int = 0,
  ): String = {
    val candidate =
      if (suffix == 0) snapshotsProfileId else s"$snapshotsProfileId-$suffix"
    if (!existingIds.contains(candidate)) candidate
    else uniqueProfileId(existingIds, suffix + 1)
  }

  private def createSnapshotProfileElement(
      doc: Document,
      ns: Option[String],
      prefix: Option[String],
      profileId: String,
  ): Element = {
    val profile = createElement(doc, ns, prefix, "profile")
    profile.appendChild(
      createTextElement(doc, ns, prefix, "id", profileId)
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

}
