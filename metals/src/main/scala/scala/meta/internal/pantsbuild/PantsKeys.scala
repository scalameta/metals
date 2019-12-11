package scala.meta.internal.pantsbuild

/** Key names from the output of `./pants export` */
object PantsKeys {
  val targets = "targets"
  val transitiveTargets = "transitive_targets"
  val libraries = "libraries"
  val isTargetRoot = "is_target_root"
  val id = "id"
  val pantsTargetType = "pants_target_type"
  val targetType = "target_type"
  val scalaPlatform = "scala_platform"
  val compilerClasspath = "compiler_classpath"
  val scalaVersion = "scala_version"
}
