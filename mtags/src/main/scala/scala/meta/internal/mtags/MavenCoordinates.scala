package scala.meta.internal.mtags

case class MavenCoordinates(
    org: String,
    name: String,
    version: String
) {
  def syntax: String = s"${org}:${name}:${version}"
}
