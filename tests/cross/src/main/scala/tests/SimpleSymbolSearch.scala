package tests

import java.nio.file.Paths
import scala.meta.inputs.Input
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

class SimpleSymbolSearch(classpath: ClasspathSearch) extends SymbolSearch {
  var source = ""
  val path = Paths.get("A.scala")
  override def search(
      textQuery: String,
      buildTargetIdentifier: String,
      visitor: SymbolSearchVisitor
  ): SymbolSearch.Result = {
    val query = WorkspaceSymbolQuery.exact(textQuery)
    SemanticdbDefinition.foreach(Input.VirtualFile("A.scala", source)) { defn =>
      if (query.matches(defn.info)) {
        val c = defn.toCached
        visitor.visitWorkspaceSymbol(path, c.symbol, c.kind, c.range)
      }
    }
    classpath.search(query, visitor)
  }
}
