package scala.meta.metals.maven

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

private[maven] object JavaHomeResolver {

  import MavenPluginSupport._

  def resolve(
      javacOptions: List[String],
      project: MavenProject,
      isTest: Boolean,
      mojo: MbtMojo,
  ): Option[String] =
    fromForkExecutable(project, isTest)
      .orElse(fromCompilerJdkToolchain(project, isTest, mojo))
      .orElse(fromMavenSessionToolchain(mojo))
      .orElse(fromToolchainsXml(project, javacOptions, isTest, mojo))
      .orElse(sys.props.get("java.home").filter(_.nonEmpty))

  private[maven] def selectJavaHome(
      project: MavenProject,
      candidates: List[String],
  ): Option[String] = {
    val requirement = javaRequirement(project)
    if (requirement.isEmpty) candidates.headOption
    else candidates.find(javaHomeSupports(_, requirement))
  }

  // Returns the JDK toolchain already selected by maven-toolchains-plugin in this session.
  private def fromMavenSessionToolchain(mojo: MbtMojo): Option[String] =
    Option(mojo.getToolchainManager)
      .flatMap(tm =>
        Option(tm.getToolchainFromBuildContext("jdk", mojo.getSession))
      )
      .flatMap(tc => Option(tc.findTool("java")))
      .flatMap(javaHomeFromExecutable)

  private[maven] def fromForkExecutable(
      project: MavenProject,
      isTest: Boolean,
      pathDirs: => Seq[String] = systemPathDirs,
  ): Option[String] = {
    val plugins = effectivePlugins(project)
    for {
      plugin <- Option(plugins.get(JavaCompilerPlugin))
      cfg <- compilerPluginConfiguration(plugin, isTest)
      _ <- childText(cfg, "fork").filter(_.equalsIgnoreCase("true"))
      rawExecutable <- childText(cfg, "executable").filter(_.nonEmpty)
      interpolated = interpolatePath(rawExecutable, project)
      resolved <- resolveExecutablePath(interpolated, project, pathDirs)
      home <- javaHomeFromExecutable(resolved)
    } yield home
  }

  private def resolveExecutablePath(
      interpolated: String,
      project: MavenProject,
      pathDirs: Seq[String],
  ): Option[Path] =
    if (
      interpolated
        .contains("/") || interpolated.contains(java.io.File.separator)
    ) {
      val path = canonicalizePath(absolutePath(interpolated, project))
      Option.when(path.isAbsolute)(path)
    } else {
      // Bare command name (e.g. "javac21") — search PATH directories
      pathDirs
        .map(dir => Path.of(dir).resolve(interpolated))
        .find(Files.isExecutable)
        .map(path => canonicalizePath(path.toString))
    }

  private[maven] def canonicalizePath(pathString: String): Path = {
    val path = Path.of(pathString)
    try path.toRealPath()
    catch {
      case NonFatal(_) => path.toAbsolutePath.normalize()
    }
  }

  private def javaHomeFromExecutable(executable: String): Option[String] =
    javaHomeFromExecutable(canonicalizePath(executable))

  private def javaHomeFromExecutable(executable: Path): Option[String] =
    for {
      parent <- Option(executable.getParent)
      grandparent <- Option(parent.getParent)
    } yield grandparent.toString

  private def systemPathDirs: Seq[String] =
    Option(System.getenv("PATH")).toList
      .flatMap(_.split(java.io.File.pathSeparator))

  private def fromCompilerJdkToolchain(
      project: MavenProject,
      isTest: Boolean,
      mojo: MbtMojo,
  ): Option[String] = {
    val plugins = effectivePlugins(project)
    for {
      plugin <- Option(plugins.get(JavaCompilerPlugin))
      cfg <- compilerPluginConfiguration(plugin, isTest)
      jdkToolchain <- Option(cfg.getChild("jdkToolchain"))
      home <- {
        val reqs = jdkToolchain.getChildren.flatMap { child =>
          Option(child.getValue).filter(_.nonEmpty).map(v => child.getName -> v)
        }.toMap
        if (reqs.isEmpty) None
        else toolchainHomes(reqs, mojo).headOption
      }
    } yield home
  }

  // Looks up toolchains by requirements from maven-toolchains-plugin config,
  // project properties, or --release / -source from javacOptions (last resort hint).
  private def fromToolchainsXml(
      project: MavenProject,
      javacOptions: List[String],
      isTest: Boolean,
      mojo: MbtMojo,
  ): Option[String] = {
    toolchainsPluginRequirements(project, isTest)
      .flatMap(r => toolchainHomes(r, mojo).headOption)
      .orElse {
        val reqs = propertyRequirements(project)
          .orElse(javacOptionsRequirements(javacOptions))
          .map(r => r.filter { case (k, _) => k == "version" })
        reqs.flatMap { r =>
          val homes = toolchainHomes(r, mojo)
          if (homes.isEmpty) None
          else selectJavaHome(project, homes)
        }
      }
  }

  private[maven] def javacOptionsRequirements(
      javacOptions: List[String]
  ): Option[Map[String, String]] = {
    val version = javacOptions
      .sliding(2)
      .collectFirst {
        case Seq("--release", v) => v
        case Seq("-source", v) => v
      }
      .orElse {
        javacOptions.collectFirst {
          case opt if opt.startsWith("--release=") =>
            opt.stripPrefix("--release=")
          case opt if opt.startsWith("-source=") => opt.stripPrefix("-source=")
        }
      }
    version.map(v => Map("version" -> v))
  }

  private def toolchainsPluginRequirements(
      project: MavenProject,
      isTest: Boolean,
  ): Option[Map[String, String]] = {
    val plugins = effectivePlugins(project)
    Option(plugins.get(MavenToolchainsPlugin)).flatMap { plugin =>
      val executionCfgs = plugin.getExecutions.asScala
        .flatMap(e => mergedPluginConfiguration(plugin, e).map(_ -> e))
        .toSeq
      val specificCfgs = executionCfgs
        .filter { case (_, e) => toolchainExecutionMatches(e, isTest) }
        .map(_._1)
      val neutralCfgs = executionCfgs
        .filter { case (_, e) => toolchainExecutionTarget(e).isEmpty }
        .map(_._1)
      val cfgs = specificCfgs ++ neutralCfgs
      val topCfg = Option(plugin.getConfiguration).map(_.asInstanceOf[Xpp3Dom])
      (cfgs ++ topCfg)
        .flatMap { cfg =>
          Option(cfg.getChild("toolchains")).toList.flatMap { toolchains =>
            Option(toolchains.getChild("jdk")).toList.map { jdk =>
              jdk.getChildren.flatMap { child =>
                Option(child.getValue)
                  .filter(_.nonEmpty)
                  .map(v => child.getName -> v)
              }.toMap
            }
          }
        }
        .filter(_.nonEmpty)
        .headOption
    }
  }

  private def toolchainExecutionMatches(
      execution: PluginExecution,
      isTest: Boolean,
  ): Boolean =
    toolchainExecutionTarget(execution).contains(isTest)

  private def toolchainExecutionTarget(
      execution: PluginExecution
  ): Option[Boolean] = {
    val values =
      Option(execution.getId).toSeq ++
        Option(execution.getPhase).toSeq ++
        execution.getGoals.asScala.toSeq
    if (values.exists(isTestExecutionValue)) Some(true)
    else if (values.exists(isMainExecutionValue)) Some(false)
    else None
  }

  private def isTestExecutionValue(value: String): Boolean = {
    val normalized = normalizeExecutionValue(value)
    val tokens = executionTokens(value)
    normalized.contains("testcompile") ||
    normalized.startsWith("test") ||
    tokens.contains("test")
  }

  private def isMainExecutionValue(value: String): Boolean = {
    val normalized = normalizeExecutionValue(value)
    val tokens = executionTokens(value)
    normalized == "compile" ||
    normalized.endsWith("compile") ||
    tokens.contains("main")
  }

  private def normalizeExecutionValue(value: String): String =
    Option(value).getOrElse("").toLowerCase.replaceAll("[^a-z0-9]", "")

  private def executionTokens(value: String): Seq[String] =
    Option(value).toSeq
      .flatMap(_.toLowerCase.split("[^a-z0-9]+"))
      .filter(_.nonEmpty)

  private def propertyRequirements(
      project: MavenProject
  ): Option[Map[String, String]] = {
    val versions = requiredJdkVersions(project)
    val vendors = requiredJdkVendors(project)
    if (versions.isEmpty && vendors.isEmpty) None
    else
      Some(
        Map.empty[String, String] ++
          versions.headOption
            .flatMap(versionMajor)
            .map(v => "version" -> v.toString) ++
          vendors.headOption.map("vendor" -> _)
      )
  }

  private case class JavaRequirement(
      major: Option[Int],
      vendors: List[String],
  ) {
    def isEmpty: Boolean = major.isEmpty && vendors.isEmpty
    override def toString: String = {
      val parts = major.map(v => s"version>=$v").toList ++
        (if (vendors.nonEmpty) List(s"vendor in [${vendors.mkString(", ")}]")
         else Nil)
      parts.mkString(", ")
    }
  }

  private def javaRequirement(project: MavenProject): JavaRequirement = {
    val versions = requiredJdkVersions(project)
    JavaRequirement(
      major = versions.flatMap(versionMajor).reduceOption(_ max _),
      vendors = requiredJdkVendors(project),
    )
  }

  private def requiredJdkVersions(project: MavenProject): List[String] = {
    val props = project.getProperties
    List(
      "air.java.version",
      "java.version",
      "jdk.version",
      "project.build.targetJdk",
    ).flatMap(key => Option(props.getProperty(key)).filter(_.nonEmpty))
  }

  private def requiredJdkVendors(project: MavenProject): List[String] =
    Option(effectivePlugins(project).get(MavenEnforcerPlugin)).toList
      .flatMap(mergedPluginConfiguration)
      .flatMap { cfg =>
        for {
          rules <- Option(cfg.getChild("rules")).toList
          requireVendor <- Option(rules.getChild("requireJavaVendor")).toList
          includes <- Option(requireVendor.getChild("includes")).toList
          include <- includes.getChildren("include").toList
          vendor <- Option(include.getValue).filter(_.nonEmpty)
        } yield vendor
      }
      .distinct

  private def toolchainHomes(
      reqs: Map[String, String],
      mojo: MbtMojo,
  ): List[String] =
    Option(mojo.getToolchainManager).toList
      .flatMap { tm =>
        Option(tm.getToolchains(mojo.getSession, "jdk", reqs.asJava)).toList
          .flatMap(_.asScala)
          .flatMap { tc =>
            Option(tc.findTool("java")).flatMap(javaHomeFromExecutable)
          }
      }

  private def javaHomeSupports(
      javaHome: String,
      requirement: JavaRequirement,
  ): Boolean = {
    val release = javaRelease(javaHome)
    val versionOk =
      requirement.major.forall(major =>
        release.flatMap(_.version).exists(_ >= major)
      )
    val vendorOk =
      requirement.vendors.isEmpty ||
        release.flatMap(_.implementor).exists { actual =>
          requirement.vendors
            .exists(required => vendorMatches(actual, required))
        }
    versionOk && vendorOk
  }

  private def vendorMatches(actual: String, required: String): Boolean = {
    val actualAliases = vendorAliases(actual)
    val requiredAliases = vendorAliases(required)
    actualAliases.exists(requiredAliases.contains)
  }

  private def vendorAliases(value: String): Set[String] = {
    val normalized = normalizeVendor(value)
    val base = Set(normalized)
    val aliases = normalized match {
      case v if v.contains("azul") || v.contains("zulu") =>
        Set("azul", "zulu", "azulsystemsinc")
      case v if v.contains("adoptium") || v.contains("temurin") =>
        Set("adoptium", "eclipseadoptium", "temurin")
      case v if v.contains("oracle") =>
        Set("oracle", "oraclecorporation")
      case v if v.contains("graalvm") =>
        Set("graalvm")
      case _ => Set.empty[String]
    }
    base ++ aliases
  }

  private def normalizeVendor(value: String): String =
    Option(value).getOrElse("").toLowerCase.replaceAll("[^a-z0-9]", "")

  private val JavaVersionRegex = """(?m)^JAVA_VERSION="([^"]+)"""".r
  private val ImplementorRegex = """(?m)^IMPLEMENTOR="([^"]+)"""".r

  private case class JavaRelease(
      version: Option[Int],
      implementor: Option[String],
  )

  private def javaRelease(javaHome: String): Option[JavaRelease] = {
    val home = Path.of(javaHome)
    val releaseFiles =
      List(
        Some(home.resolve("release")),
        Option(home.getParent).map(_.resolve("release")),
      ).flatten
    releaseFiles
      .find(Files.isRegularFile(_))
      .map { releaseFile =>
        val text = Files.readString(releaseFile)
        JavaRelease(
          version = JavaVersionRegex
            .findFirstMatchIn(text)
            .map(_.group(1))
            .flatMap(versionMajor),
          implementor = ImplementorRegex.findFirstMatchIn(text).map(_.group(1)),
        )
      }
  }

  private def versionMajor(version: String): Option[Int] =
    version match {
      case null => None
      case v if v.startsWith("1.") =>
        v.drop(2).takeWhile(_.isDigit).toIntOption
      case v =>
        v.takeWhile(_.isDigit).toIntOption
    }
}
