package scala.meta.internal.metals

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage

/**
 * Gson TypeAdapterFactory that injects W3C trace context (traceparent,
 * tracestate) into outgoing BSP RequestMessage params during JSON
 * serialization.
 *
 * The injected fields are prefixed with underscore (e.g. _traceparent) and
 * are silently ignored by non-OTEL-aware BSP servers.  When no OTEL SDK is
 * on the classpath the no-op propagator leaves the carrier empty and no
 * extra fields are added.
 */
class TraceContextAdapterFactory extends TypeAdapterFactory {

  override def create[T](gson: Gson, tpe: TypeToken[T]): TypeAdapter[T] = {
    if (!classOf[Message].isAssignableFrom(tpe.getRawType)) return null

    // Get the adapter that would be used without this factory (the lsp4j-default adapter)
    val delegate = gson.getDelegateAdapter(this, tpe)

    new TypeAdapter[T] {
      override def write(out: JsonWriter, value: T): Unit = {
        value match {
          case _: RequestMessage =>
            // Serialize to tree via delegate so all lsp4j type adapters still apply
            val tree = delegate.toJsonTree(value)
            val obj = tree.getAsJsonObject
            injectTraceIntoParams(obj)
            gson.toJson(tree, out)
          case _ =>
            delegate.write(out, value)
        }
      }

      override def read(in: JsonReader): T = delegate.read(in)
    }
  }

  private def injectTraceIntoParams(
      messageObj: com.google.gson.JsonObject
  ): Unit = {
    if (messageObj.has("params") && messageObj.get("params").isJsonObject) {
      val params = messageObj.getAsJsonObject("params")
      val carrier = new java.util.HashMap[String, String]()
      MetalsTracer.injectTraceContext(carrier)
      if (!carrier.isEmpty) {
        carrier.forEach { (k, v) =>
          params.addProperty("_" + k, v)
        }
      }
    }
  }
}
