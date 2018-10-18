package scala.meta.internal.metals

import scala.meta.io.AbsolutePath

sealed abstract class BuildTool extends Product with Serializable

object BuildTool {
  def autoDetect(workspace: AbsolutePath): BuildTool = {
    if (workspace.resolve(".bloop").isDirectory) Bloop
    else if (workspace.resolve("build.sbt").isFile) Sbt
    else if (workspace.resolve("build.sc").isFile) Mill
    else if (workspace.resolve("build.gradle").isFile) Gradle
    else if (workspace.resolve("pom.xml").isFile) Maven
    else if (workspace.resolve("pants.ini").isFile) Pants
    else if (workspace.resolve("WORKSPACE").isFile) Bazel
    else Unknown
  }
  case object Bazel extends BuildTool
  case object Bloop extends BuildTool
  case object Gradle extends BuildTool
  case object Maven extends BuildTool
  case object Mill extends BuildTool
  case object Pants extends BuildTool
  case object Sbt extends BuildTool
  case object Unknown extends BuildTool
}
