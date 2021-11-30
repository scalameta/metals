package tests

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.io.AbsolutePath

import munit.Location
import tests.MetalsTestEnrichments._

abstract class BaseWorkspaceSymbolSuite extends BaseSuite {
  def workspace: AbsolutePath
  def libraries: List[Library] = Nil
  def dialect: Dialect = dialects.Scala213
  lazy val symbols: WorkspaceSymbolProvider = {
    val p = TestingWorkspaceSymbolProvider(workspace)
    p.indexWorkspace(dialect)
    p.indexLibraries(libraries)
    p.indexClasspath()
    p
  }
  def check(
      query: String,
      expected: String
  )(implicit loc: Location): Unit = {
    test(query) {
      val result = symbols.search(query)
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
