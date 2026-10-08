package scala.meta.internal.metals

sealed trait JavaFormatterConfig

object JavaFormatterConfig {
  case object Eclipse extends JavaFormatterConfig
  case object GoogleJavaFormat extends JavaFormatterConfig
  case object None extends JavaFormatterConfig
}
