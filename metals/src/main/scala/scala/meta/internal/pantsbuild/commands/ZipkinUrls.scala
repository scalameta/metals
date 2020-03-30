package scala.meta.internal.pantsbuild.commands

object ZipkinUrls {
  def url: Option[String] =
    Option(System.getProperty("metals.zipkin.server.url"))
}
