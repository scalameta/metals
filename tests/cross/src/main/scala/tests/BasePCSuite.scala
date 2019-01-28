package tests

import com.geirsson.coursiersmall.CoursierSmall
import com.geirsson.coursiersmall.Dependency
import com.geirsson.coursiersmall.Settings
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import scala.collection.JavaConverters._
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.MetalsSymbolIndexer
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.util.Properties

abstract class BasePCSuite extends BaseSuite {
  val scalaLibrary: Seq[Path] =
    this.getClass.getClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .iterator
      .filter(_.getPath.contains("scala-library"))
      .map(url => Paths.get(url.toURI))
      .toSeq
  def extraClasspath: List[Path] = Nil
  val myclasspath: List[Path] = extraClasspath ++ scalaLibrary.toList
  val index = OnDemandSymbolIndex()
  val indexer = new MetalsSymbolIndexer(index)
  val search = new SimpleSymbolSearch(
    ClasspathSearch.fromClasspath(myclasspath, _ => 0)
  )
  val pc = new ScalaPresentationCompiler()
    .withIndexer(indexer)
    .withSearch(search)
    .newInstance("", myclasspath.asJava, Nil.asJava)

  def indexJDK(): Unit = {
    index.addSourceJar(JdkSources().get)
  }
  def indexScalaLibrary(): Unit = {
    val sources = CoursierSmall.fetch(
      new Settings()
        .withClassifiers(List("sources"))
        .withDependencies(
          List(
            new Dependency(
              "org.scala-lang",
              "scala-library",
              BuildInfoVersions.scala212
            )
          )
        )
    )
    sources.foreach { jar =>
      index.addSourceJar(AbsolutePath(jar))
    }
  }

  override def beforeAll(): Unit = {
    indexJDK()
    indexScalaLibrary()
  }
  override def afterAll(): Unit = {
    pc.shutdown()
  }
  def params(code: String): (String, Int) = {
    val code2 = code.replaceAllLiterally("@@", "")
    val offset = code.indexOf("@@")
    if (offset < 0) {
      fail("missing @@")
    }
    search.source = code2
    (code2, offset)
  }
  def doc(e: JEither[String, MarkupContent]): String = {
    if (e == null) ""
    else if (e.isLeft) {
      " " + e.getLeft
    } else {
      " " + e.getRight.getValue
    }
  }.trim
  private def scalaVersion: String =
    Properties.versionNumberString
  private def scalaBinary: String =
    scalaVersion.split("\\.").take(2).mkString(".")
  def getExpected[T](default: T, compat: Map[String, T]): T = {
    compat
      .get(scalaBinary)
      .orElse(compat.get(scalaVersion))
      .getOrElse(default)
  }
}
