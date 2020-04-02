package scala.meta.internal.pantsbuild.commands

import scala.meta.internal.pantsbuild.commands.BloopGlobalSettings.Property

object BloopZipkinTraceProperties {

  val localServiceName: Property = Property(
    "metals.bloop.trace.localServiceName"
  )

  val traceStartAnnotation: Property = Property(
    "metals.bloop.trace.traceStartAnnotation"
  )

  val traceEndAnnotation: Property = Property(
    "metals.bloop.trace.traceEndAnnotation"
  )
}
