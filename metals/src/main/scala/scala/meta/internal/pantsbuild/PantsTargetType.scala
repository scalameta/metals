package scala.meta.internal.pantsbuild

case class PantsTargetType(value: String) {
  def isJarLibrary: Boolean = value == "jar_library"
  def isJvmBinary: Boolean = value == "jvm_binary"
  def isJvmApp: Boolean = value == "jvm_app"
  def isScalaLibrary: Boolean = value == "scala_library"
  def isTarget: Boolean = value == "target"
  def isResources: Boolean = value == "resources"
  def isJavaTests: Boolean = value == "java_tests"
  def isJUnitTests: Boolean = value == "junit_tests"
  def isJavaLibrary: Boolean = value == "java_library"
  def isJavaThriftLibrary: Boolean = value == "java_thrift_library"
  def isJavaAntlrLibrary: Boolean = value == "java_antrl_library"
  def isJavaFiles: Boolean = value == "java_files"
  def isFiles: Boolean = value == "files"
  def isAlias: Boolean = value == "alias"
  def isNodeModule: Boolean = value == "node_module"

  def isScalaOrJavaLibrary: Boolean = isScalaLibrary || isJavaLibrary
  def isSupported: Boolean =
    !PantsTargetType.unsupportedTargetType.contains(value)
}

object PantsTargetType {
  private val unsupportedTargetType = Set(
    "files", "page", "python_binary", "python_tests", "python_library",
    "python_requirement_library"
  )
}
