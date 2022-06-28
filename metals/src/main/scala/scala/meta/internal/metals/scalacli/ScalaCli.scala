package scala.meta.internal.metals.scalacli

import java.io.File
import java.nio.file.Paths

import scala.jdk.CollectionConverters._
import scala.util.Properties

import scala.meta.internal.metals.BuildInfo

object ScalaCli {

  lazy val javaCommand: String = {

    val defaultJvmId = "temurin:17"

    val majorVersion = sys.props
      .get("java.version")
      .map(_.takeWhile(_.isDigit))
      .filter(_.nonEmpty)
      .flatMap(_.toIntOption)
      .getOrElse(0)

    val ext = if (Properties.isWin) ".exe" else ""
    if (majorVersion >= 17)
      Paths
        .get(sys.props("java.home"))
        .resolve(s"bin/java$ext")
        .toAbsolutePath
        .toString
    else {
      scribe.info(
        s"Found Java version $majorVersion. " +
          s"Scala CLI requires at least Java 17, getting a $defaultJvmId JVM via coursier-jvm."
      )
      val jvmManager = coursierapi.JvmManager.create()
      val javaHome = jvmManager.get(defaultJvmId)
      new File(javaHome, s"bin/java$ext").getAbsolutePath
    }
  }

  def scalaCliClassPath(): Seq[String] =
    coursierapi.Fetch
      .create()
      .addDependencies(
        coursierapi.Dependency
          .of("org.virtuslab.scala-cli", "cli_3", BuildInfo.scalaCliVersion)
      )
      .fetch()
      .asScala
      .toSeq
      .map(_.getAbsolutePath)

  def scalaCliMainClass: String =
    "scala.cli.ScalaCli"

}
