package tests.telemetry

import java.util.Optional
import java.{util => ju}

import scala.util.Random.nextBoolean

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.telemetry

object SampleReports {
  private case class OptionalControl(setEmpty: Boolean)
  private case class MapControl(setEmpty: Boolean)
  private case class ListControl(setEmpty: Boolean)

  private def optional[T](value: => T)(implicit
      ctrl: OptionalControl
  ): Optional[T] =
    if (ctrl.setEmpty) Optional.empty() else Optional.of(value)
  private def maybeEmptyMap[K, V](values: (K, V)*)(implicit
      ctrl: MapControl
  ): ju.Map[K, V] =
    if (ctrl.setEmpty) ju.Collections.emptyMap else values.toMap.asJava
  private def maybeEmptyList[T](values: T*)(implicit
      ctrl: ListControl
  ): ju.List[T] =
    if (ctrl.setEmpty) ju.Collections.emptyList else values.asJava

  private def reportOf(ctx: telemetry.ReporterContextUnion)(implicit
      opt: OptionalControl,
      list: ListControl,
  ): telemetry.ErrorReport = new telemetry.ErrorReport(
    "name",
    optional("text"),
    optional("id"),
    optional(
      new telemetry.ExceptionSummary(
        maybeEmptyList("ExceptionType"),
        "stacktrace",
      )
    ),
    "reporterName",
    ctx,
    new telemetry.Environment(
      new telemetry.JavaInfo("version", "distiribution"),
      new telemetry.SystemInfo("arch", "name", "version"),
    ),
  )

  private def presentationCompilerConfig()(implicit
      opt: OptionalControl,
      map: MapControl,
      list: ListControl,
  ) = new telemetry.PresentationCompilerConfig(
    maybeEmptyMap("symbol" -> "prefix"),
    optional("command"),
    optional("parameterHints"),
    "overrideDefFormat",
    nextBoolean,
    nextBoolean,
    nextBoolean,
    nextBoolean,
    nextBoolean,
    nextBoolean,
    nextBoolean,
    nextBoolean,
    maybeEmptyList("semanticDbOpts"),
  )

  def metalsLspReport(
      emptyOptionals: Boolean = false,
      emptyLists: Boolean = false,
      emptyMaps: Boolean = false,
  ): telemetry.ErrorReport = {
    implicit val ctrl: OptionalControl = OptionalControl(!emptyOptionals)
    implicit val map: MapControl = MapControl(!emptyMaps)
    implicit val list: ListControl = ListControl(!emptyLists)
    reportOf(
      telemetry.ReporterContextUnion.metalsLSP(
        new telemetry.MetalsLspContext(
          "metalsVersion",
          new telemetry.MetalsUserConfiguration(
            maybeEmptyMap("symbol" -> "prefix"),
            nextBoolean(),
            optional("bloopVersion"),
            maybeEmptyList("props"),
            maybeEmptyList("ammoniteProps"),
            nextBoolean(),
            optional("inferedTypes"),
            nextBoolean(),
            nextBoolean(),
            nextBoolean(),
            nextBoolean(),
            nextBoolean(),
            maybeEmptyList("package.name"),
            optional("fallback version"),
            "testUserInterface",
          ),
          new telemetry.MetalsServerConfiguration(
            "clientCommand",
            nextBoolean(),
            nextBoolean(),
            nextBoolean(),
            nextBoolean(),
            nextBoolean(),
            presentationCompilerConfig,
          ),
          new telemetry.MetalsClientInfo(
            optional("name"),
            optional("version"),
          ),
          maybeEmptyList(
            new telemetry.BuildServerConnection(
              "connection.name",
              "connection.version",
              util.Random.nextBoolean(),
            )
          ),
        )
      )
    )
  }

  def scalaPresentationCompilerReport(
      emptyOptionals: Boolean = false,
      emptyLists: Boolean = false,
      emptyMaps: Boolean = false,
  ): telemetry.ErrorReport = {
    implicit val ctrl: OptionalControl = OptionalControl(!emptyOptionals)
    implicit val map: MapControl = MapControl(!emptyMaps)
    implicit val list: ListControl = ListControl(!emptyLists)

    reportOf(
      telemetry.ReporterContextUnion.scalaPresentationCompiler(
        new telemetry.ScalaPresentationCompilerContext(
          "scalaVersion",
          maybeEmptyList("options", "othersOptions"),
          presentationCompilerConfig(),
        )
      )
    )
  }
}
