package scala.meta.internal.zipkin

object ZipkinProperties {

  val zipkinServerUrl: Property = Property("metals.zipkin.server.url")

  val localServiceName: Property = Property(
    "metals.bloop.trace.localServiceName"
  )

  val traceStartAnnotation: Property = Property(
    "metals.bloop.trace.traceStartAnnotation"
  )

  val traceEndAnnotation: Property = Property(
    "metals.bloop.trace.traceEndAnnotation"
  )

  val All: List[Property] = List(
    zipkinServerUrl,
    localServiceName,
    traceStartAnnotation,
    traceEndAnnotation
  )
}
