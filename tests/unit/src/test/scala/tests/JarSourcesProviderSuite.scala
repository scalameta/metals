package tests

import scala.meta.internal.metals.JarSourcesProvider
import scala.meta.internal.metals.MetalsEnrichments._

class JarSourcesProviderSuite extends BaseSuite {

  test("download-deps") {
    val downloadedSources =
      JarSourcesProvider.fetchSources(Library.cats.map(_.toURI.toString()))
    assert(downloadedSources.nonEmpty)
    downloadedSources.foreach { pathStr =>
      val path = pathStr.toAbsolutePath
      assert(path.exists)
      assert(path.filename.endsWith("-sources.jar"))
    }
  }

}
