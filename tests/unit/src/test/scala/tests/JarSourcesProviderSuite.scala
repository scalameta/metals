package tests

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.JarSourcesProvider
import scala.meta.internal.metals.MetalsEnrichments._

import coursier.core.Dependency
import coursier.core.Module
import coursier.core.ModuleName
import coursier.core.Organization

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

  private val sbtDap = {
    val attributes = Map("scalaVersion" -> "2.12", "sbtVersion" -> "1.0")
    val module = Module(
      Organization("ch.epfl.scala"),
      ModuleName("sbt-debug-adapter"),
      attributes,
    )
    Dependency(module, "3.1.4")
  }

  private val metalsSbt = {
    val attributes = Map("scalaVersion" -> "2.12", "sbtVersion" -> "1.0")
    val module = Module(
      Organization("org.scalameta"),
      ModuleName("sbt-metals"),
      attributes,
    )
    Dependency(module, "1.1.0")
  }

  test("download-sbt-plugins") {
    for {
      sources <- Future.sequence(
        List(sbtDap, metalsSbt).map(JarSourcesProvider.fetchDependencySources)
      )
      _ = sources.foreach(sources => assert(sources.nonEmpty))
    } yield ()
  }

}
