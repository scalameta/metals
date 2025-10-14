package scala.meta.internal.metals

import java.io.StringReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import coursierapi.Credentials
import coursierapi.IvyRepository
import coursierapi.MavenRepository
import coursierapi.Repository

object CoursierHelpers {
  def defaultRepositories: List[Repository] =
    // Reimplment how `COURSIER_REPOSITORIES` is interpreted by Coursier CLI
    // since the Java interfaces don't correctly assign credentials to each
    // host. See https://github.com/coursier/interface/issues/460
    // NOTE: The env variable `COURSIER_REPOSITORIES` is automatically set by
    // the metals-vscode extension. The idiomatic thing would be to read this
    // via LSP user configuration, if someone wants to implement that.
    Option(System.getenv("COURSIER_REPOSITORIES")) match {
      case Some(value) =>
        val parts = value.split("\\|").toList
        val repos = parseCustomRepositories(parts)
        scribe.debug(
          s"Using custom Coursier repositories from env: ${repos}"
        )
        repos
      case None =>
        Repository.defaults().asScala.toList ++
          List(
            Repository.central(),
            Repository.ivy2Local(),
            MavenRepository.of(
              "https://oss.sonatype.org/content/repositories/public/"
            ),
            MavenRepository.of(
              "https://oss.sonatype.org/content/repositories/snapshots/"
            ),
          )
    }
  def repoHost(r: Repository): Option[String] = {
    r match {
      case m: MavenRepository =>
        Try(URI.create(m.getBase()).getHost()).toOption
      case _ => None
    }
  }

  def parseRepository(s: String): Try[Repository] = Try {
    if (s == "central") {
      Repository.central()
    } else if (s.compareToIgnoreCase("ivy2local") == 0) {
      Repository.ivy2Local()
    } else if (s.startsWith("sonatype:")) {
      MavenRepository.of(
        s"https://oss.sonatype.org/content/repositories/${s.stripPrefix("sonatype:")}"
      )
    } else if (s.startsWith("sonatype-s01:")) {
      MavenRepository.of(
        s"https://s01.oss.sonatype.org/content/repositories/${s.stripPrefix("sonatype-s01:")}"
      )
    } else if (s == "google") {
      MavenRepository.of("https://maven.google.com")
    } else if (s.startsWith("ivy:")) {
      val s0 = s.stripPrefix("ivy:")
      val sepIdx = s0.indexOf('|')
      if (sepIdx < 0)
        IvyRepository.of(s0)
      else {
        val mainPart = s0.substring(0, sepIdx)
        val metadataPart = s0.substring(sepIdx + 1)
        IvyRepository.of(mainPart, metadataPart)
      }
    } else {
      MavenRepository.of(s)
    }

  }

  private def withCredentials(
      repo: Repository,
      credentials: Map[String, Credentials],
  ): Option[Repository] = {
    for {
      m <- repo match {
        case m: MavenRepository => Some(m)
        case _ => None
      }
      host <- repoHost(m)
      creds <- credentials.get(host)
    } yield {
      scribe.info(s"Using custom credentials for $host: $creds")
      m.withCredentials(creds)
    }
  }

  def parseCustomRepositories(
      customRepositories: List[String]
  ): List[Repository] = {
    if (customRepositories.isEmpty) {
      return defaultRepositories
    }
    val credentials = envCredentials()
    for {
      r <- customRepositories
      repo <- parseRepository(r.trim()) match {
        case Failure(ex) =>
          scribe.warn(s"Ignoring invalid Coursier repository '$r'", ex)
          Nil
        case Success(value) =>
          List(value)
      }
    } yield withCredentials(repo, credentials).getOrElse(repo)
  }
  private def envCredentials(): Map[String, Credentials] =
    credentialsFromFile(
      Paths.get(
        System.getProperty("user.home"),
        ".config",
        "coursier",
        "credentials.properties",
      )
    )
  private def credentialsFromFile(path: Path): Map[String, Credentials] = {
    if (!Files.exists(path)) {
      Map.empty
    } else {
      try {
        val props = new java.util.Properties()
        val text = Files.readString(path)
        props.load(new StringReader(text))
        val kv = props.asScala
        // The credential key is "gh" from the following example config:
        //   gh.username=olaf-geirsson_data
        //   gh.password=ghp_TOKEN
        //   gh.host=maven.pkg.github.com
        val credentialKeys =
          kv.keys.map(_.split("\\.", 2)).collect { case Array(k, _) => k }
        val result = for {
          key <- credentialKeys
          host <- kv.get(s"$key.host").toList
          username <- kv.get(s"$key.username").toList
          password <- kv.get(s"$key.password").toList
        } yield (host, Credentials.of(username, password))
        result.toMap
      } catch {
        case NonFatal(ex) =>
          scribe.warn(s"Failed to load Coursier credentials from $path", ex)
          Map.empty
      }
    }
  }
}
