package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import metaconfig.annotation._
import metaconfig.generic.Settings
import metaconfig.{ConfDecoder, ConfEncoder}

case class ListProjects(
    @Inline common: SharedOptions = SharedOptions()
)
object ListProjects {
  val default: ListProjects = ListProjects()
  implicit lazy val surface: generic.Surface[ListProjects] =
    generic.deriveSurface[ListProjects]
  implicit lazy val encoder: ConfEncoder[ListProjects] =
    generic.deriveEncoder[ListProjects]
  implicit lazy val decoder: ConfDecoder[ListProjects] =
    generic.deriveDecoder[ListProjects](default)
  implicit lazy val settings: Settings[ListProjects] = Settings[ListProjects]
}
