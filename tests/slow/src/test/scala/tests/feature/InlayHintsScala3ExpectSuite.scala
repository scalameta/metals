package tests.feature

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.ProgressTicks
import scala.meta.inputs.Position
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.{BuildInfo => V}

import tests.DirectoryExpectSuite
import scala.meta.internal.metals.InlayHintsProvider
import tests.ExpectTestCase
import tests.FakeTime
import tests.InputProperties
import tests.TestMtagsResolver
import tests.TestingClient
import scala.meta.internal.metals.CompilerRangeParams
import tests.TreeUtils
import scala.meta.internal.metals.CompilerSyntheticDecorationsParams
import tests.TestInlayHints
import scala.meta.internal.metals.UserConfiguration

class InlayHintsScala3ExpectSuite(
) extends DirectoryExpectSuite("inlayHints3") {
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
          val rangeParams = CompilerRangeParams(
            file.file.toURI,
            file.code,
            0,
            file.code.length,
            EmptyCancelToken,
          )
          val pos = Position.Range(file.input, 0, file.code.length)
          val inlayHintsProvider =
            new InlayHintsProvider(rangeParams, trees, () => userConfig, pos)
          val withoutTypes = inlayHintsProvider.withoutTypes
          val pcParams = CompilerSyntheticDecorationsParams(
            rangeParams,
            withoutTypes.asJava,
            true,
            true,
            true,
          )
          val decorations =
            compiler.syntheticDecorations(pcParams).get().asScala.toList
          val inlayHints = inlayHintsProvider.provide(
            decorations
          )
          TestInlayHints.applyInlayHints(file.code, inlayHints)
        },
      )
    }
  }

  override def afterAll(): Unit = {
    compiler.shutdown()
  }
}
