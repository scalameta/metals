package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.util.control.NonFatal

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.ExcludedPackagesHandler
import scala.meta.internal.metals.JdkSources
import scala.meta.io.AbsolutePath
import scala.meta.pc.ReportContext

import coursierapi.Fetch
import coursierapi.Repository
import munit.Assertions.fail

trait PCSuite {

  def dialect: Dialect

  def tmp: AbsolutePath

  protected val index = new DelegatingGlobalSymbolIndex()
  protected val workspace = new TestingWorkspaceSearch

  protected val allRepos: Seq[Repository] =
    Repository.defaults().asScala.toSeq

  protected def fetch: Fetch = Fetch
    .create()
    .withRepositories(allRepos: _*)

  protected def indexJdkSources: Unit = JdkSources() match {
    case Right(jdk) => index.addSourceJar(jdk, dialects.Scala213)
    case _ =>
  }

  protected def extraLibraries(f: Fetch): Seq[Path] = f
    .fetch()
    .asScala
    .map(_.toPath())
    .toSeq

  protected def search(
      myclasspath: Seq[Path]
  )(implicit rc: ReportContext = EmptyReportContext): TestingSymbolSearch = {
    new TestingSymbolSearch(
      ClasspathSearch
        .fromClasspath(myclasspath, ExcludedPackagesHandler.default),
      new Docstrings(index),
      workspace,
      index
    )
  }

  def params(code: String, filename: String): (String, Int) = {
    val code2 = code.replace("@@", "")
    val offset = code.indexOf("@@")
    if (offset < 0) {
      fail("missing @@")
    }

    addSourceToIndex(filename, code2)
    (code2, offset)
  }

  def hoverParams(
      code: String,
      filename: String = "test.java"
  ): (String, Int, Int) = {
    val code2 = code.replace("@@", "").replace("%<%", "").replace("%>%", "")
    val positionOffset =
      code.replace("%<%", "").replace("%>%", "").indexOf("@@")
    val startOffset = code.replace("@@", "").indexOf("%<%")
    val endOffset = code.replace("@@", "").replace("%<%", "").indexOf("%>%")
    (positionOffset, startOffset, endOffset) match {
      case (po, so, eo) if po < 0 && so < 0 && eo < 0 =>
        fail("missing @@ and (%<% and %>%)")
      case (_, so, eo) if so >= 0 && eo >= 0 =>
        (code2, so, eo)
      case (po, _, _) =>
        addSourceToIndex(filename, code2)
        (code2, po, po)
    }
  }

  private def addSourceToIndex(filename: String, code2: String): Unit = {
    val file = tmp.resolve(filename)
    Files.createDirectories(file.toNIO.getParent)
    Files.write(file.toNIO, code2.getBytes(StandardCharsets.UTF_8))
    try index.addSourceFile(file, Some(tmp), dialect)
    catch {
      case NonFatal(e) =>
        println(s"warn: ${e.getMessage}")
    }
    workspace.inputs(file.toURI.toString()) = (code2, dialect)
  }

}
