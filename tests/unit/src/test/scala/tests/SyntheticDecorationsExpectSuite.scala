package tests

import scala.meta.internal.metals.CompilerSyntheticDecorationsParams
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.pc.ScalaPresentationCompiler

import org.eclipse.lsp4j.TextEdit

class SyntheticDecorationsExpectSuite
    extends DirectoryExpectSuite("decorations") {

  override lazy val input: InputProperties = InputProperties.scala2()
  private val compiler = new ScalaPresentationCompiler(
    classpath = input.classpath.entries.map(_.toNIO)
  )
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
          val pcParams = CompilerSyntheticDecorationsParams(
            vFile,
            true,
            true,
            true,
            true,
          )
          val decorations =
            compiler.syntheticDecorations(pcParams).get().asScala.toList
          val edits = decorations.map { d =>
            new TextEdit(
              d.range,
              "/*" + d.label + "*/",
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
