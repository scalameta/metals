import sbt._
import sbt.Keys._

import com.github.sbt.JavaFormatterPlugin
import com.github.sbt.JavaFormatterPlugin.autoImport._

object MetalsBuildPlugin extends AutoPlugin {
  override def trigger = allRequirements

  override def requires = JavaFormatterPlugin

  override def projectSettings = Seq(Compile, Test).flatMap(config =>
    inConfig(config)(
      javafmt / sourceDirectories := (config / sourceDirectories).value
    )
  )
}
