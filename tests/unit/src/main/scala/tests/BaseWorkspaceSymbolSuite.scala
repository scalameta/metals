package tests

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.io.AbsolutePath

import munit.Location
import org.eclipse.lsp4j.SymbolInformation
import tests.MetalsTestEnrichments._

abstract class BaseWorkspaceSymbolSuite extends BaseSuite {
  def workspace: AbsolutePath
  def libraries: List[Library] = Nil
  def dialect: Dialect = dialects.Scala213
  def saveClassFileToDisk: Boolean
  lazy val symbols: WorkspaceSymbolProvider = {
    val p = TestingWorkspaceSymbolProvider(workspace, saveClassFileToDisk)
    p.indexWorkspace(dialect)
    p.indexLibraries(libraries)
    p.indexClasspath()
    p
  }
  def check(
      query: String,
      expected: String,
      filter: SymbolInformation => Boolean = _ => true,
  )(implicit loc: Location): Unit = {
    test(query) {
      val result = symbols.search(query).filter(filter)
      val obtained =
        if (result.length > 100) s"${result.length} results"
        else {
          result
            .map { i => s"${i.getContainerName}${i.getName} ${i.getKind}" }
            .sorted
            .mkString("\n")
        }
      assertNoDiff(obtained, expected)
    }
  }
}
