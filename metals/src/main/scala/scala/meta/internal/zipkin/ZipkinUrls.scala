package scala.meta.internal.zipkin

object ZipkinUrls {
  def url: Option[String] = {
    Option(System.getProperty("metals.zipkin.server.url"))
  }
}
