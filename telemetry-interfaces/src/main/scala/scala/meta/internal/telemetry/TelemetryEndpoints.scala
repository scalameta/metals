package scala.meta.internal.telemetry

import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.jsoniter._

object TelemetryEndpoints {
  val baseEndpoint: Endpoint[Unit, Unit, Unit, Unit, Any] = endpoint.in("v1.0")

  val sendErrorReport: PublicEndpoint[ErrorReport, Unit, Unit, Any] =
    baseEndpoint.post
      .in("reporting" / "sendErrorReport")
      .in(jsonBody[ErrorReport])

  val sendCrashReport: PublicEndpoint[CrashReport, Unit, Unit, Any] =
    baseEndpoint.post
      .in("reporting" / "sendCrashReport")
      .in(jsonBody[CrashReport])

}
