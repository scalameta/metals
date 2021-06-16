package scala.meta.internal.metals

import java.nio.file.FileSystems

import scala.collection.JavaConverters._
import scala.util.Success
import scala.util.Try

import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax
import com.typesafe.config.parser.ConfigDocument
import com.typesafe.config.parser.ConfigDocumentFactory

/**
 * A partial repersentation of scalafmt config format.
 * Includes only settings that affect dialect.
 */
case class ScalafmtConfig(
    version: Option[SemVer.Version],
    runnerDialect: Option[ScalafmtDialect],
    fileOverrides: List[(ScalafmtConfig.PathMatcher, ScalafmtDialect)],
    includeFilters: List[ScalafmtConfig.PathMatcher],
    excludeFilters: List[ScalafmtConfig.PathMatcher]
) {

  def overrideFor(path: AbsolutePath): Option[ScalafmtDialect] = {
    fileOverrides.collectFirst {
      case (pm, dialect) if pm.matches(path) => dialect
    }
  }

  def isExcluded(path: AbsolutePath): Boolean = {
    excludeFilters.exists(_.matches(path)) && !includeFilters.exists(
      _.matches(path)
    )
  }
}

object ScalafmtConfig {

  val empty: ScalafmtConfig =
    ScalafmtConfig(None, None, List.empty, List.empty, List.empty)

  sealed trait PathMatcher {
    def matches(path: AbsolutePath): Boolean
  }
  object PathMatcher {
    final case class Nio(pattern: String) extends PathMatcher {
      private val matcher = FileSystems.getDefault().getPathMatcher(pattern)
      def matches(path: AbsolutePath): Boolean = matcher.matches(path.toNIO)
    }

    final case class Regex(regex: String) extends PathMatcher {
      private val pattern = java.util.regex.Pattern.compile(regex)
      def matches(path: AbsolutePath): Boolean =
        pattern.matcher(path.toString).find()
    }
  }

  /**
   * Notice: due to problems with config library there is no  way to merge `fileOverride`.
   * Don't specify this value in case if it's already defined in config
   */
  def update(
      configText: String,
      version: Option[String] = None,
      runnerDialect: Option[ScalafmtDialect] = None,
      fileOverride: Map[String, ScalafmtDialect] = Map.empty
  ): String = {

    def docFrom(s: String): ConfigDocument = {
      val options = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF)
      ConfigDocumentFactory.parseString(s, options)
    }

    def withUpdatedVersion(content: String, v: String): String = {
      val doc = docFrom(content)
      if (doc.hasPath("version"))
        doc.withValueText("version", '"' + v + '"').render
      else {
        // prepend to the beggining of file
        val sb = new StringBuilder
        sb.append(s"""version = "$v"""")
        sb.append(System.lineSeparator)
        sb.append(content)
        sb.toString
      }
    }

    def withUpdatedDialect(content: String, d: ScalafmtDialect): String = {
      val doc = docFrom(content)
      if (doc.hasPath("runner.dialect"))
        doc.withValueText("runner.dialect", d.value).render
      else {
        // append to the end
        val sb = new StringBuilder
        sb.append(content)
        val sep = System.lineSeparator
        val lastLn = content.endsWith(sep)
        if (!lastLn) sb.append(sep)
        sb.append(s"runner.dialect = ${d.value}")
        sb.append(sep)
        sb.toString
      }
    }

    def withFileOverride(
        content: String,
        overrides: Map[String, ScalafmtDialect]
    ): String = {
      if (overrides.isEmpty) content
      else {
        val sep = System.lineSeparator
        val values = overrides
          .map { case (key, dialect) =>
            s"""|  "$key" {
                |     runner.dialect = ${dialect.value}
                |  }""".stripMargin
          }
          .mkString(s"fileOverride {$sep", sep, s"$sep}$sep")

        val addSep = if (content.endsWith(sep)) "" else sep
        content + addSep + values
      }
    }

    val doNothing = identity[String] _
    val combined = List(
      version.fold(doNothing)(v => withUpdatedVersion(_, v)),
      runnerDialect.fold(doNothing)(v => withUpdatedDialect(_, v)),
      withFileOverride(_, fileOverride)
    ).reduceLeft(_ andThen _)
    combined(configText)
  }

  def parse(path: AbsolutePath): Try[ScalafmtConfig] =
    Try(ConfigFactory.parseFile(path.toFile)).flatMap(parse)

  def parse(text: String): Try[ScalafmtConfig] =
    Try(ConfigFactory.parseString(text)).flatMap(parse)

  def parse(config: Config): Try[ScalafmtConfig] = {

    def getVersion(conf: Config): Try[Option[SemVer.Version]] =
      if (conf.hasPath("version"))
        Try(SemVer.Version.fromString(conf.getString("version")))
          .map(Some(_))
      else Success(None)

    def getRunnerDialectRaw(conf: Config): Option[ScalafmtDialect] = {
      if (conf.hasPath("runner.dialect")) {
        val v = conf.getString("runner.dialect")
        ScalafmtDialect.fromString(v)
      } else
        None
    }

    def getRunnerDialect(
        conf: Config
    ): Try[Option[ScalafmtDialect]] = {
      Try(getRunnerDialectRaw(conf))
    }

    def getFileOverrides(
        conf: Config
    ): Try[List[(PathMatcher, ScalafmtDialect)]] = {
      Try {
        if (conf.hasPath("fileOverride")) {
          val obj = conf.getObject("fileOverride")
          val asConfig = obj.toConfig()
          val keys = obj.keySet().asScala
          keys.toList
            .map { key =>
              val quotedKey = '"' + key + '"'
              val innerCfg = asConfig.getConfig(quotedKey)
              val dialect = getRunnerDialectRaw(innerCfg)
              key -> dialect
            }
            .collect { case (glob, Some(dialect)) =>
              val matcher = PathMatcher.Nio(glob)
              matcher -> dialect
            }
        } else List.empty
      }
    }

    def readMatchers(conf: Config, path: String)(
        f: String => PathMatcher
    ): Try[List[PathMatcher]] = {
      Try {
        if (config.hasPath(path)) {
          config
            .getStringList(path)
            .asScala
            .map(f)
            .toList
        } else {
          List.empty
        }
      }
    }

    def filters(
        conf: Config,
        key: String
    ): Try[List[PathMatcher]] =
      readMatchers(conf, s"project.$key")(v => PathMatcher.Regex(v))

    def paths(
        conf: Config,
        key: String
    ): Try[List[PathMatcher]] =
      readMatchers(conf, s"project.$key")(v => PathMatcher.Nio(v))

    for {
      version <- getVersion(config)
      runnerDialect <- getRunnerDialect(config)
      overrides <- getFileOverrides(config)
      includeFilters <- filters(config, "includeFilters")
      excludeFilters <- filters(config, "excludeFilters")
      includePaths <- paths(config, "includePaths")
      excludePaths <- paths(config, "excludePaths")
    } yield {
      val include = includePaths ++ includeFilters
      val exclude = excludeFilters ++ excludePaths
      ScalafmtConfig(version, runnerDialect, overrides, include, exclude)
    }
  }
}
