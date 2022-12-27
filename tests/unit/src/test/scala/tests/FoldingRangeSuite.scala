package tests

import java.nio.file.Paths
import java.util.UUID

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.parsing.FoldingRangeProvider
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}
import tests.BuildInfo.testResourceDirectory

abstract class FoldingRangeSuite(
    scalaVersion: String,
    directory: String,
    lineFoldingOnly: Boolean,
) extends DirectoryExpectSuite(s"$directory/expect") {
  private val (buffers, trees) = TreeUtils.getTrees(scalaVersion)
  private val foldingRangeProvider = new FoldingRangeProvider(trees, buffers)
  foldingRangeProvider.setFoldOnlyLines(lineFoldingOnly)

  override def testCases(): List[ExpectTestCase] = {
    val inputDirectory = AbsolutePath(testResourceDirectory)
      .resolve(directory)
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
    val source = file.input.text

    val actualRanges = findFoldingRangesFor(source, file.file.extension)
    val edits = RangesTextEdits.fromFoldingRanges(actualRanges)
    TextEdits.applyEdits(source, edits)
  }

  private def findFoldingRangesFor(
      source: String,
      extension: String,
  ): java.util.List[l.FoldingRange] = {
    val path = registerSource(source, extension)
    if (path.isScala) foldingRangeProvider.getRangedForScala(path)
    else foldingRangeProvider.getRangedForJava(path)
  }

  private def registerSource(
      source: String,
      extension: String,
  ): AbsolutePath = {
    val name = UUID.randomUUID().toString + "." + extension
    val path = AbsolutePath(Paths.get(name))
    buffers.put(path, source)
    path
  }
}

class FoldingRangeScala2Suite
    extends FoldingRangeSuite(
      V.scala213,
      "foldingRange",
      lineFoldingOnly = false,
    )
class FoldingRangeScala3Suite
    extends FoldingRangeSuite(
      V.scala3,
      "foldingRange-scala3",
      lineFoldingOnly = false,
    )

class FoldingRangeScala3LineFoldingOnlySuite
    extends FoldingRangeSuite(
      V.scala3,
      "foldingRange-scala3-foldLineOnly",
      lineFoldingOnly = true,
    )
