package tests

import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.io.AbsolutePath
import tests.MetalsTestEnrichments._

abstract class BaseWorkspaceSymbolSuite extends BaseSuite {
  def workspace: AbsolutePath
  def statistics: StatisticsConfig = StatisticsConfig.default
  def libraries: List[Library] = Nil
  lazy val symbols: WorkspaceSymbolProvider = {
    val p = TestingWorkspaceSymbolProvider(workspace, statistics = statistics)
    p.indexWorkspace()
    p.indexLibraries(libraries)
    p.indexClasspath()
    p
  }
  def check(
      query: String,
      expected: String
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    test(query) {
      val result = symbols.search(query, target = None)
      val obtained =
        if (result.length > 100) s"${result.length} results"
        else {
          result
            .map { i =>
              s"${i.getContainerName}${i.getName} ${i.getKind}"
            }
            .sorted
            .mkString("\n")
        }
      assertNoDiff(obtained, expected)
    }
  }
}
