package tests

import java.nio.file.Paths
import scala.collection.mutable
import scala.meta.inputs.Input
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.pc.SymbolSearchVisitor

object TestingWorkspaceSearch {
  def empty: TestingWorkspaceSearch = new TestingWorkspaceSearch
}

class TestingWorkspaceSearch {
  val inputs = mutable.Map.empty[String, String]
  def search(query: WorkspaceSymbolQuery, visitor: SymbolSearchVisitor): Unit =
    for {
      (path, text) <- inputs
    } {
      SemanticdbDefinition.foreach(Input.VirtualFile(path, text)) { defn =>
        if (query.matches(defn.info)) {
          val c = defn.toCached
          visitor.visitWorkspaceSymbol(
            Paths.get(path),
            c.symbol,
            c.kind,
            c.range
          )
        }
      }
    }
}
