package scala.meta.internal.metals

final case class StatisticsConfig(value: String) {
  val isAll: Boolean = value == "all"
  val isMemory: Boolean = isAll || value.contains("memory")
  val isDefinition: Boolean = isAll || value.contains("definition")
  val isDiagnostics: Boolean = isAll || value.contains("diagnostics")
  val isReferences: Boolean = isAll || value.contains("references")
  val isWorkspaceSymbol: Boolean = isAll || value.contains("workspace-symbol")
  val isIndex: Boolean = isAll || value.contains("index")
  val isTreeView: Boolean = isAll || value.contains("tree-view")
}

object StatisticsConfig {
  def all = new StatisticsConfig("all")
  def workspaceSymbol = new StatisticsConfig("workspace-symbol")
  def default =
    new StatisticsConfig(
      System.getProperty("metals.statistics", "default")
    )
}
