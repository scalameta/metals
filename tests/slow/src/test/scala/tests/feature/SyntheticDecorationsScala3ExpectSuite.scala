package tests.feature

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.CompilerSyntheticDecorationsParams
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.SyntheticDecorationsProvider
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}

import org.eclipse.lsp4j.TextEdit
import tests.DirectoryExpectSuite
import tests.ExpectTestCase
import tests.FakeTime
import tests.InputProperties
import tests.TestMtagsResolver
import tests.TestingClient
import tests.TreeUtils

class SyntheticDecorationsScala3ExpectSuite(
) extends DirectoryExpectSuite("decorations3") {
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
  private val (_, trees) = TreeUtils.getTrees(V.scala3)
  val userConfig: UserConfiguration = UserConfiguration().copy(
    showInferredType = Some("true"),
    showImplicitArguments = true,
    showImplicitConversionsAndClasses = true,
  )

  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file,
        () => {
          val vFile = CompilerVirtualFileParams(
            file.file.toURI,
            file.code,
            EmptyCancelToken,
          )
          val syntheticDecorationsProvider =
            new SyntheticDecorationsProvider(vFile, trees, () => userConfig)
          val withoutTypes = syntheticDecorationsProvider.declsWithoutTypes
          val pcParams = CompilerSyntheticDecorationsParams(
            vFile,
            withoutTypes.asJava,
            true,
            true,
            true,
          )
          val decorations = syntheticDecorationsProvider.provide(
            compiler.syntheticDecorations(pcParams).get().asScala.toList
          )
          val edits = decorations.map { d =>
            new TextEdit(
              d.range,
              "/*" + d.renderOptions.after.contentText + "*/",
            )
          }

          TextEdits.applyEdits(file.code, edits)
        },
      )
    }
  }

  override def afterAll(): Unit = {
    compiler.shutdown()
  }
}
