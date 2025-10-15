package scala.meta.internal.metals

import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.util.Try

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

case class JdkVersion(
    major: Int,
    full: String,
)

object JdkVersion {

  def maybeJdkVersionFromJavaHome(
      maybeJavaHome: String
  )(implicit ec: ExecutionContext): Option[JdkVersion] =
    maybeJdkVersionFromJavaHome(Try(AbsolutePath(maybeJavaHome)).toOption)

  def maybeJdkVersionFromJavaHome(
      maybeJavaHome: Option[AbsolutePath]
  )(implicit ec: ExecutionContext): Option[JdkVersion] = {
    maybeJavaHome.flatMap { javaHome =>
      fromReleaseFile(javaHome).orElse {
        fromShell(javaHome)
      }
    }
  }

  def fromShell(
      javaHome: AbsolutePath
  )(implicit ec: ExecutionContext): Option[JdkVersion] = {
    ShellRunner
      .runSync(
        List(javaHome.resolve("bin/java").toString, "-version"),
        javaHome,
        redirectErrorOutput = true,
        maybeJavaHome = Some(javaHome.toString()),
      )
      .flatMap { javaVersionResponse =>
        "\\d+\\.\\d+\\.\\d+".r
          .findFirstIn(javaVersionResponse)
          .flatMap(JdkVersion.parse)
      }
  }

  def fromReleaseFile(javaHome: AbsolutePath): Option[JdkVersion] =
    fromReleaseFileString(javaHome).flatMap(f => parse(f))

  def fromReleaseFileString(javaHome: AbsolutePath): Option[String] =
    Seq(javaHome.resolve("release"), javaHome.parent.resolve("release"))
      .filter(_.exists)
      .flatMap { releaseFile =>
        val props = new ju.Properties
        props.load(Source.fromFile(releaseFile.toFile).bufferedReader())
        props.asScala
          .get("JAVA_VERSION")
          .map(_.stripPrefix("\"").stripSuffix("\""))
      }
      .headOption

  def parse(v: String): Option[JdkVersion] = {
    val numbers = Try {
      v
        .split('-')
        .head
        .split('.')
        .toList
        .take(2)
        .flatMap(s => Try(s.toInt).toOption)
    }.toOption

    numbers match {
      case Some(1 :: minor :: _) =>
        Some(JdkVersion(minor, v))
      case Some(single :: _) =>
        Some(JdkVersion(single, v))
      case _ => None
    }
  }
}
