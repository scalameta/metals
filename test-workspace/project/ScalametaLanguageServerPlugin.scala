import sbt._
import sbt.Keys._
import java.io._

object ScalametaLanguageServerPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin
  val scalametaCompilerConfig =
    taskKey[String]("Configuration parameters for autocompletion.")
  val scalametaEnableCompletions =
    taskKey[Unit]("Setup environment for scalameta/language-server")
  override lazy val globalSettings = List(
    // `*:scalametaSetupCompletions` sets up all configuration in all projects (note *: prefix, that's needed!)
    scalametaEnableCompletions := Def.taskDyn {
      val filter = ScopeFilter(inAnyProject, inConfigurations(Compile, Test))
      scalametaEnableCompletions.all(filter)
    }.value
  )
  override lazy val projectSettings = List(Compile, Test).flatMap { c =>
    inConfig(c)(
      Seq(
        scalametaCompilerConfig := {
          val props = new java.util.Properties()
          props.setProperty(
            "scalacOptions",
            scalacOptions.value.mkString(" ")
          )
          props.setProperty(
            "classpath",
            fullClasspath.value
              .map(_.data.toString)
              .mkString(File.pathSeparator)
          )
          props.setProperty(
            "sources",
            sources.value.distinct.mkString(File.pathSeparator)
          )
          def libraryDependencyToString(m: ModuleID): String = {
            // HACK(olafur) This will not work for js/native, figure out
            // a the correct way to do this.
            val cross = m.crossVersion match {
              case _: CrossVersion.Full => "_" + scalaVersion.value
              case _: CrossVersion.Binary =>
                "_" + scalaBinaryVersion.value
              case _ => ""
            }
            s"${m.organization}:${m.name}${cross}:${m.revision}"
          }
          props.setProperty(
            "libraryDependencies",
            libraryDependencies.value
              .map(libraryDependencyToString)
              .mkString(";")
          )
          val out = new ByteArrayOutputStream()
          props.store(out, null)
          out.toString()
        },
        scalametaEnableCompletions := {
          val f = target.value / (c.name + ".compilerconfig")
          IO.write(f, scalametaCompilerConfig.value)
          streams.value.log.info(
            "Wrote language-server configuration to: " + f.getAbsolutePath
          )
        }
      )
    )
  }
}
