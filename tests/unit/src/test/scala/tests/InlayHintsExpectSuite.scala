package tests

import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.InlayHintsProvider
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.metals.UserConfiguration
import scala.meta.inputs.Position


class InlayHintsExpectSuite extends DirectoryExpectSuite("inlayHints") {

  override lazy val input: InputProperties = InputProperties.scala2()
  private val compiler = new ScalaPresentationCompiler(
    classpath = input.classpath.entries.map(_.toNIO)
  )
  val userConfig: UserConfiguration = UserConfiguration().copy(
    showInferredType = Some("true"),
    showImplicitArguments = true,
    showImplicitConversionsAndClasses = true,
  )

  private val (_, trees) = TreeUtils.getTrees(V.scala213)
  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file,
        () => {
          val params = CompilerRangeParams(
            file.file.toURI,
            file.code,
            0,
            file.code.length,
            EmptyCancelToken,
          )
          val decorations = compiler.syntheticDecorations(params).get().asScala.toList

          val pos = Position.Range(file.input, 0, file.code.length)
          val inlayHintsProvider = new InlayHintsProvider(params, trees, () => userConfig, pos)
          val inlayHints = inlayHintsProvider.provide(
            decorations,
          )
          TestInlayHints.applyInlayHints(file.code, inlayHints)
        }
      )
    }
  }

  override def afterAll(): Unit = {
    compiler.shutdown()
  }
}