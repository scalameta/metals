package scala.meta.internal.telemetry

import sttp.tapir._
import sttp.tapir.json.jsoniter._
import sttp.tapir.generic.auto._

object TelemetryEndpoints {
  val baseEndpoint = endpoint.in("v1.0")

  val sendReport: PublicEndpoint[ErrorReport, Unit, Unit, Any] =
    baseEndpoint.post
      .in("reporting" / "sendReport")
      .in(jsonBody[ErrorReport])
}
