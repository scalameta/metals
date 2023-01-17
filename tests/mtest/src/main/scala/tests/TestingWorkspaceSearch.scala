package tests

import java.nio.file.Paths

import scala.collection.mutable

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.metals.WorkspaceSymbolInformation
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.pc.SymbolSearchVisitor

object TestingWorkspaceSearch {
  def empty: TestingWorkspaceSearch = new TestingWorkspaceSearch
}

class TestingWorkspaceSearch {
  val inputs: mutable.Map[String, (String, Dialect)] =
    mutable.Map.empty[String, (String, Dialect)]
  def search(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor,
      filter: WorkspaceSymbolInformation => Boolean = _ => true,
  ): Unit =
    for {
      (path, (text, dialect)) <- inputs
    } {
      SemanticdbDefinition.foreach(Input.VirtualFile(path, text), dialect) {
        defn =>
          if (query.matches(defn.info)) {
            val c = defn.toCached
            if (filter(c)) {
              visitor.visitWorkspaceSymbol(
                Paths.get(path),
                c.symbol,
                c.kind,
                c.range,
              )
            }
          }
      }
    }
}
