package scala.meta.internal.metals

final case class StatisticsConfig(value: String) {
  def isAll: Boolean = value == "all"
  def isMemory: Boolean = isAll || value.contains("memory")
  def isDefinition: Boolean = isAll || value.contains("definition")
}

object StatisticsConfig {
  def all = new StatisticsConfig("all")
  def default = new StatisticsConfig(
    System.getProperty("metals.statistics", "default")
  )
}
