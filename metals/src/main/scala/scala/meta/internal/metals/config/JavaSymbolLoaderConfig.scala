package scala.meta.internal.metals.config

sealed trait JavaSymbolLoaderConfig {
  def isTurbineClasspath: Boolean = false
  def isJavacSourcepath: Boolean = false
}

object JavaSymbolLoaderConfig {
  case object TurbineClasspath extends JavaSymbolLoaderConfig {
    override def toString: String = "turbine-classpath"
    override def isTurbineClasspath: Boolean = true
  }
  case object JavacSourcepath extends JavaSymbolLoaderConfig {
    override def toString: String = "javac-sourcepath"
    override def isJavacSourcepath: Boolean = true
  }
}
