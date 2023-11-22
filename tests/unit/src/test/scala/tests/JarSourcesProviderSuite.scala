package tests

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.JarSourcesProvider
import scala.meta.internal.metals.MetalsEnrichments._

class JarSourcesProviderSuite extends BaseSuite {

  private implicit val ctx: ExecutionContext = this.munitExecutionContext

  test("download-deps") {
    for {
      downloadedSources <- JarSourcesProvider.fetchSources(
        Library.cats.map(_.toURI.toString())
      )
    } yield {
      assert(downloadedSources.nonEmpty)
      downloadedSources.foreach { pathStr =>
        val path = pathStr.toAbsolutePath
        assert(path.exists)
        assert(path.filename.endsWith("-sources.jar"))
      }
    }
  }

}
