package tests

import com.geirsson.coursiersmall.CoursierSmall
import com.geirsson.coursiersmall.Dependency
import com.geirsson.coursiersmall.Settings
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import scala.collection.JavaConverters._
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig
import scala.util.Properties
import scala.util.control.NonFatal

abstract class BasePCSuite extends BaseSuite {
  def thisClasspath: Seq[Path] =
    this.getClass.getClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .map(url => Paths.get(url.toURI))
  val scalaLibrary: Seq[Path] =
    this.getClass.getClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .iterator
      .filter(_.getPath.contains("scala-library"))
      .map(url => Paths.get(url.toURI))
      .toSeq
  def extraClasspath: Seq[Path] = Nil
  def scalacOptions: Seq[String] = Nil
  def config: PresentationCompilerConfig = PresentationCompilerConfigImpl()
  val myclasspath: Seq[Path] = extraClasspath ++ scalaLibrary.toList
  val index = OnDemandSymbolIndex()
  val indexer = new Docstrings(index)
  val workspace = new TestingWorkspaceSearch
  val search = new TestingSymbolSearch(
    ClasspathSearch.fromClasspath(myclasspath, _ => 0),
    new Docstrings(index),
    workspace
  )
  val pc = new ScalaPresentationCompiler()
    .withSearch(search)
    .withConfiguration(config)
    .newInstance("", myclasspath.asJava, scalacOptions.asJava)
  val tmp = AbsolutePath(Files.createTempDirectory("metals"))

  def indexJDK(): Unit = {
    JdkSources().foreach(jdk => index.addSourceJar(jdk))
  }

  override def test(name: String)(fun: => Any): Unit = {
    // We are unable to infer the JDK jars on Appveyor
    // tests.BasePCSuite.indexJDK(BasePCSuite.scala:44)
    if (isWindows) ignore(name)(())
    else {
      val testName =
        if (Properties.versionNumberString != BuildInfoVersions.scala212)
          s"${Properties.versionNumberString}-${name}"
        else name
      super.test(testName)(fun)
    }
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

  override def afterAll(): Unit = {
    pc.shutdown()
    RecursivelyDelete(tmp)
  }
  def params(code: String, filename: String = "test.scala"): (String, Int) = {
    val code2 = code.replaceAllLiterally("@@", "")
    val offset = code.indexOf("@@")
    if (offset < 0) {
      fail("missing @@")
    }
    val file = tmp.resolve(filename)
    Files.write(file.toNIO, code2.getBytes(StandardCharsets.UTF_8))
    try index.addSourceFile(file, Some(tmp))
    catch {
      case NonFatal(e) =>
        println(s"warn: $e")
    }
    workspace.inputs(filename) = code2
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
  val compatProcess = Map.empty[String, String => String]
  def getExpected(default: String, compat: Map[String, String]): String = {
    val postProcess = compatProcess
      .get(scalaBinary)
      .orElse(compatProcess.get(scalaVersion))
      .getOrElse(identity[String] _)
    val result = compat
      .get(scalaBinary)
      .orElse(compat.get(scalaVersion))
      .getOrElse(default)
    postProcess(result)
  }
  def sortLines(stableOrder: Boolean, string: String): String =
    if (stableOrder) string
    else string.lines.toList.sorted.mkString("\n")
}
