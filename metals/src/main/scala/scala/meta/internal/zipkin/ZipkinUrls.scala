package scala.meta.internal.zipkin
import scala.meta.internal.pantsbuild.commands.BloopGlobalSettings.Property

object ZipkinUrls {

  val zipkinServerUrl: Property = Property("metals.zipkin.server.url")
}
