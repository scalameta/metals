package tests.feature

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.SemanticTokensProvider
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.{BuildInfo => V}

import tests.DirectoryExpectSuite
import tests.ExpectTestCase
import tests.FakeTime
import tests.InputProperties
import tests.TestMtagsResolver
import tests.TestSemanticTokens
import tests.TestingClient

class SemanticTokensScala3ExpectSuite(
) extends DirectoryExpectSuite("semanticTokens3") {
  override lazy val input: InputProperties = InputProperties.scala3()
  private val compiler = {
    val resolver = new TestMtagsResolver()
    resolver.resolve(V.scala3) match {

      case Some(mtags: MtagsBinaries.Artifacts) =>
        val time = new FakeTime
        val client = new TestingClient(PathIO.workingDirectory, Buffers())
        val status = new StatusBar(
          client,
          time,
          ProgressTicks.dots,
          ClientConfiguration.default,
        )(munitExecutionContext)
        val embedded = new Embedded(status)
        embedded
          .presentationCompiler(mtags, mtags.jars)
          .newInstance(
            "tokens",
            input.classpath.entries.map(_.toNIO).asJava,
            Nil.asJava,
          )
      case _ => fail(s"Could not load ${V.scala3} presentation compiler")
    }

  }

  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file,
        () => {
          val params = CompilerVirtualFileParams(
            file.file.toURI,
            file.code,
            EmptyCancelToken,
          )
          val nodes = compiler.semanticTokens(params).get().asScala.toList

          val tokens = SemanticTokensProvider.provide(
            nodes,
            params,
            isScala3 = true,
          )

          TestSemanticTokens.semanticString(
            file.code,
            tokens.toList.map(_.toInt),
          )
        },
      )
    }
  }

  override def afterAll(): Unit = {
    compiler.shutdown()
  }
}
