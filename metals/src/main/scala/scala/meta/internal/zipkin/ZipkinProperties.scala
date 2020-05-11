package scala.meta.internal.zipkin

object ZipkinProperties {

  val zipkinServerUrl: Property = Property("metals.zipkin.server.url")

  val debugTracing: Property = Property("metals.bloop.tracing.debugTracing")

  val verbose: Property = Property("metals.bloop.tracing.verbose")

  val localServiceName: Property = Property(
    "metals.bloop.tracing.localServiceName"
  )

  val traceStartAnnotation: Property = Property(
    "metals.bloop.tracing.traceStartAnnotation"
  )

  val traceEndAnnotation: Property = Property(
    "metals.bloop.tracing.traceEndAnnotation"
  )

  val All: List[Property] = List(
    zipkinServerUrl,
    debugTracing,
    verbose,
    localServiceName,
    traceStartAnnotation,
    traceEndAnnotation
  )
}
