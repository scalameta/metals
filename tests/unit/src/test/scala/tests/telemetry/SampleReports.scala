package tests.telemetry

import java.{util => ju}

import scala.util.Random.nextBoolean

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.telemetry

object SampleReports {
  private case class OptionalControl(setEmpty: Boolean)
  private case class MapControl(setEmpty: Boolean)
  private case class ListControl(setEmpty: Boolean)

  private def reportOf(ctx: telemetry.ReporterContext): telemetry.ErrorReport =
    new telemetry.ErrorReport(
      name = "name",
      text = Some("text"),
      reporterContext = ctx,
      id = Some("id"),
      error = Some(
        telemetry.ExceptionSummary(
          "ExceptionType",
          List(telemetry.StackTraceElement("fullyQualifiedName", "methodName", "fileName", 0)),
        )
      ),
      reporterName = "reporterName",
      env = telemetry.Environment(
        new telemetry.JavaInfo("version", "distiribution"),
        new telemetry.SystemInfo("arch", "name", "version"),
      ),
    )

  private def presentationCompilerConfig() =
    new telemetry.PresentationCompilerConfig(
      Map.from(List("symbol" -> "prefix")),
      Some("command"),
      Some("parameterHints"),
      "overrideDefFormat",
      nextBoolean(),
      nextBoolean(),
      nextBoolean(),
      nextBoolean(),
      nextBoolean(),
      nextBoolean(),
      nextBoolean(),
      nextBoolean(),
      List("semanticDbOpts"),
    )

  def metalsLspReport(): telemetry.ErrorReport = {
    reportOf(
      telemetry.MetalsLspContext(
        "metalsVersion",
        telemetry.MetalsUserConfiguration(
          Map.from(List("symbol" -> "prefix")),
          nextBoolean(),
          Some("bloopVersion"),
          List("props"),
          List("ammoniteProps"),
          nextBoolean(),
          Some("inferedTypes"),
          nextBoolean(),
          nextBoolean(),
          nextBoolean(),
          nextBoolean(),
          nextBoolean(),
          List("package.name"),
          Some("fallback version"),
          "testUserInterface",
        ),
        telemetry.MetalsServerConfiguration(
          "clientCommand",
          nextBoolean(),
          nextBoolean(),
          nextBoolean(),
          nextBoolean(),
          nextBoolean(),
          presentationCompilerConfig,
        ),
        telemetry.MetalsClientInfo(
          Some("name"),
          Some("version"),
        ),
        List(
          telemetry.BuildServerConnection(
            "connection.name",
            "connection.version",
            util.Random.nextBoolean(),
          )
        ),
      )
    )
  }

  def scalaPresentationCompilerReport(): telemetry.ErrorReport = {

    reportOf(
      new telemetry.ScalaPresentationCompilerContext(
        "scalaVersion",
        List("options", "othersOptions"),
        presentationCompilerConfig(),
      )
    )
  }
}
