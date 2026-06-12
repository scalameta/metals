package scala.meta.internal.metals

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapPropagator

/**
 * Thin facade over the OpenTelemetry API.
 *
 * When no OTEL SDK is on the classpath, GlobalOpenTelemetry.get() returns a
 * no-op provider and all spans are zero-cost no-ops.
 *
 * To enable real tracing, add an OTEL Java agent or SDK+exporter JAR to the
 * editor's JVM arguments.
 */
object MetalsTracer {

  private val tracer: Tracer =
    GlobalOpenTelemetry.get().tracerBuilder("metals").build()

  private val propagator: TextMapPropagator =
    GlobalOpenTelemetry.getPropagators().getTextMapPropagator()

  def startSpan(name: String, attributes: (String, String)*): Span = {
    val builder = tracer.spanBuilder(name)
    attributes.foreach { case (k, v) => builder.setAttribute(k, v) }
    builder.startSpan()
  }

  def endSpan(span: Span): Unit = {
    span.end()
  }

  def addEvent(span: Span, event: String): Unit = {
    span.addEvent(event)
  }

  def recordException(span: Span, throwable: Throwable): Unit = {
    span.recordException(throwable)
    span.setStatus(StatusCode.ERROR, throwable.getMessage)
  }

  def currentContext(): Context = {
    Context.current()
  }

  /**
   * Injects the current W3C trace context (traceparent, tracestate) into
   * the given string-keyed map carrier.  When no OTEL SDK is on the classpath,
   * the no-op propagator is used and the carrier is left unchanged.
   */
  def injectTraceContext(carrier: java.util.Map[String, String]): Unit = {
    val setter: io.opentelemetry.context.propagation.TextMapSetter[
      java.util.Map[String, String]
    ] =
      (map: java.util.Map[String, String], k: String, v: String) =>
        map.put(k, v)
    propagator.inject(Context.current(), carrier, setter)
  }
}
