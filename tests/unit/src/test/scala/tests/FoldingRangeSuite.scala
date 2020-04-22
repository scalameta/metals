package tests

import java.nio.file.Paths
import java.util.UUID

import scala.meta.internal.metals.FoldingRangeProvider
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.Trees
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}
import tests.BuildInfo.testResourceDirectory

class FoldingRangeSuite extends DirectoryExpectSuite("foldingRange/expect") {
  private val trees = new Trees()

  private val foldingRangeProvider =
    new FoldingRangeProvider(trees, foldOnlyLines = false)

  override def testCases(): List[ExpectTestCase] = {
    val inputDirectory = AbsolutePath(testResourceDirectory)
      .resolve("foldingRange")
      .resolve("input")
    val customInput = InputProperties.fromDirectory(inputDirectory)
    customInput.allFiles.flatMap { file =>
      // pos endLine is sometimes line before on windows
      val ignored = Set("PatternMatching.scala")
      if (ignored(file.file.toFile.getName())) {
        None
      } else {
        Some(ExpectTestCase(file, () => obtainFrom(file)))
      }
    }
  }

  private def obtainFrom(file: InputFile): String = {
    val scalaSource = file.input.text

    val actualRanges = findFoldingRangesFor(scalaSource)
    val edits = FoldingRangesTextEdits.apply(actualRanges)
    TextEdits.applyEdits(scalaSource, edits)
  }

  private def findFoldingRangesFor(
      source: String
  ): java.util.List[l.FoldingRange] = {
    val path = registerSource(source)
    foldingRangeProvider.getRangedFor(path.toURI, source)
  }

  private def registerSource(source: String): AbsolutePath = {
    val name = UUID.randomUUID().toString + ".scala"
    val path = AbsolutePath(Paths.get(name))
    trees.didChange(path.toURI, source)
    path
  }
}
