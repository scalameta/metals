package scala.meta.internal.metals

final case class StatisticsConfig(value: String) {
  val isAll: Boolean = value == "all"
  val isMemory: Boolean = isAll || value.contains("memory")
  val isDefinition: Boolean = isAll || value.contains("definition")
  val isDiagnostics: Boolean = isAll || value.contains("diagnostics")
}

object StatisticsConfig {
  def all = new StatisticsConfig("all")
  def default = new StatisticsConfig(
    System.getProperty("metals.statistics", "default")
  )
}
