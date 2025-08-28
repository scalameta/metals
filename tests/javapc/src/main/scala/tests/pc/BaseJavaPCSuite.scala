package tests.pc

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.SeqHasAsJava

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.pc.JavaPresentationCompiler
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompiler

import coursierapi.Dependency
import tests.BaseSuite
import tests.PCSuite

abstract class BaseJavaPCSuite extends BaseSuite with PCSuite {

  val documentationHoverEnabled = false

  val dialect: Dialect = dialects.Scala213

  val tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("java.metals"))

  protected lazy val presentationCompiler: PresentationCompiler = {
    val fetchRepos = fetch
    extraDependencies.foreach(fetchRepos.addDependencies(_))

    val myclasspath: Seq[Path] = extraLibraries(fetchRepos)

    indexJdkSources

    JavaPresentationCompiler()
      .withSearch(search(myclasspath))
      .withConfiguration(
        PresentationCompilerConfigImpl()
          .copy(isHoverDocumentationEnabled = documentationHoverEnabled)
      )
      .newInstance("", myclasspath.asJava, Nil.asJava, Nil.asJava)
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
