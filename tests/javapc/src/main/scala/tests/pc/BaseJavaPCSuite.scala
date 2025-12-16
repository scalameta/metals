package tests.pc

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.jpc.JavaPresentationCompiler
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.io.AbsolutePath
import scala.meta.pc.EmbeddedClient

import coursierapi.Dependency
import org.slf4j.LoggerFactory
import tests.BaseSuite
import tests.PCSuite
class TestingEmbeddedClient(val tmp: AbsolutePath) extends EmbeddedClient {
  override def javaHeaderCompilerPluginJarPath(): Path = {
    val bytes = InputStreamIO.readBytes(
      this.getClass.getClassLoader
        .getResourceAsStream("java-header-compiler.jar")
    )
    val path = tmp.resolve("java-header-compiler.jar")
    Files.write(path.toNIO, bytes)
    path.toNIO
  }
  override def jdkSourcesReadonlyDir(): Path = tmp.toNIO.resolve("jdk-sources")
  override def targetDir(): Path = tmp.toNIO.resolve("target")
}

abstract class BaseJavaPCSuite extends BaseSuite with PCSuite {

  val documentationHoverEnabled = false

  val dialect: Dialect = dialects.Scala213

  val tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("java.metals"))

  protected lazy val presentationCompiler: JavaPresentationCompiler = {
    val fetch = createFetch()
    extraDependencies.foreach(fetch.addDependencies(_))

    val myclasspath: Seq[Path] = extraLibraries(fetch)
    val dependencySources = fetch.withClassifiers(Set("sources").asJava).fetch()
    dependencySources.asScala.foreach { jar =>
      index.addSourceJar(AbsolutePath(jar), dialect)
    }

    indexJdkSources()

    JavaPresentationCompiler()
      .withSearch(search(myclasspath))
      .withConfiguration(
        PresentationCompilerConfigImpl()
          .copy(
            isHoverDocumentationEnabled = documentationHoverEnabled,
            emitDiagnostics = true,
          )
      )
      .withLogger(LoggerFactory.getLogger("java.metals"))
      // TODO: we need a real instance of the embedded client here.
      .withEmbeddedClient(new TestingEmbeddedClient(tmp))
      .newInstance("", myclasspath.asJava, Nil.asJava, () => Nil.asJava)
      .asInstanceOf[JavaPresentationCompiler]
  }

  override def params(
      code: String,
      filename: String = "test.java",
  ): (String, Int) = super.params(code, filename)

  override def hoverParams(
      code: String,
      filename: String = "test.scala",
  ): (String, Int, Int) = super.hoverParams(code, filename)

  protected def extraDependencies: Seq[Dependency] =
    Seq.empty
}
