package scala.meta.internal.metals

sealed abstract class BuildTool extends Product with Serializable

object BuildTool {
  case object Bsp extends BuildTool
  case object Bazel extends BuildTool
  case object Bloop extends BuildTool
  case object Gradle extends BuildTool
  case object Maven extends BuildTool
  case object Mill extends BuildTool
  case object Pants extends BuildTool
  case class Sbt(version: String) extends BuildTool
  case object Unknown extends BuildTool
}
