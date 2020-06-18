package scala.meta.internal.pantsbuild

/**
 * Key names from the output of `./pants export` */
object PantsKeys {
  val targets = "targets"
  val excludes = "excludes"
  val transitiveTargets = "transitive_targets"
  val libraries = "libraries"
  val compileLibraries = "compile_libraries"
  val runtimeLibraries = "runtime_libraries"
  val isTargetRoot = "is_target_root"
  val id = "id"
  val pantsTargetType = "pants_target_type"
  val targetType = "target_type"
  val scalaPlatform = "scala_platform"
  val compilerClasspath = "compiler_classpath"
  val scalaVersion = "scala_version"
  val roots = "roots"
  val sourceRoot = "source_root"
  val preferredJvmDistributions = "preferred_jvm_distributions"
  val platform = "platform"
  val jvmPlatforms = "jvm_platforms"
  val defaultPlatform = "default_platform"
  val java8 = "java8"
  val strict = "strict"
  val scalacArgs = "scalac_args"
  val javacArgs = "javac_args"
  val extraJvmOptions = "extra_jvm_options"
}
