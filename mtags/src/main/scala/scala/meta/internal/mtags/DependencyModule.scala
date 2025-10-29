package scala.meta.internal.mtags

import scala.meta.io.AbsolutePath

case class DependencyModule(
    coordinates: MavenCoordinates,
    jar: AbsolutePath,
    sources: Option[AbsolutePath]
)
