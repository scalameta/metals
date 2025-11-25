package tests

import java.nio.file.Paths

import scala.collection.mutable

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.metals.WorkspaceSymbolInformation
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.mtags.ScalametaCommonEnrichments.XtensionWorkspaceSymbolQuery
import scala.meta.pc.SymbolSearchVisitor
import scala.meta.pc.reports.EmptyReportContext
import scala.meta.pc.reports.ReportContext

object TestingWorkspaceSearch {
  def empty(implicit
      rc: ReportContext = new EmptyReportContext()
  ): TestingWorkspaceSearch = new TestingWorkspaceSearch
}

class TestingWorkspaceSearch(implicit
    rc: ReportContext = new EmptyReportContext()
) {
  val inputs: mutable.Map[String, (String, Dialect)] =
    mutable.Map.empty[String, (String, Dialect)]
  def search(
      query: WorkspaceSymbolQuery,
      visitor: SymbolSearchVisitor,
      filter: WorkspaceSymbolInformation => Boolean = _ => true
  ): Unit =
    for {
      (path, (text, dialect)) <- inputs
    } {
      SemanticdbDefinition.foreach(
        Input.VirtualFile(path, text),
        dialect,
        includeMembers = true
      ) { defn =>
        if (query.matches(defn.info)) {
          val c = defn.toCached
          if (filter(c)) {
            visitor.visitSymbol(
              Paths.get(path),
              c.symbol,
              c.kind,
              c.range,
              true
            )
          }
        }
      }
    }
}
