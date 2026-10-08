package scala.meta.internal.metals.config

sealed trait TargetBuildTool

object TargetBuildTool {
  case object Sbt extends TargetBuildTool {
    override def toString: String = "sbt"
  }
  case object Gradle extends TargetBuildTool {
    override def toString: String = "gradle"
  }
  case object Maven extends TargetBuildTool {
    override def toString: String = "mvn"
  }
  case object Mill extends TargetBuildTool {
    override def toString: String = "mill"
  }
  case object ScalaCli extends TargetBuildTool {
    override def toString: String = "scala-cli"
  }
  case object Bazel extends TargetBuildTool {
    override def toString: String = "bazel"
  }
  case object Deder extends TargetBuildTool {
    override def toString: String = "deder"
  }
  case object None extends TargetBuildTool {
    override def toString: String = "none"
  }
}
