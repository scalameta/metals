package tests

import java.nio.file.Paths
import java.util.UUID
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}
import tests.BuildInfo.testResourceDirectory
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.FoldingRangeProvider
import scala.meta.io.AbsolutePath

object FoldingRangeSuite extends DirectoryExpectSuite("foldingRange/expect") {
  private val buffers = Buffers()
  private val trees = TestingTrees(buffers)

  private val foldingRangeProvider =
    new FoldingRangeProvider(trees, foldOnlyLines = false)

  override def testCases(): List[ExpectTestCase] = {
    val inputDirectory = AbsolutePath(testResourceDirectory)
      .resolve("foldingRange")
      .resolve("input")
    val customInput = InputProperties.fromDirectory(inputDirectory)
    customInput.allFiles.map { file =>
      ExpectTestCase(file, () => obtainFrom(file))
    }
  }

  private def obtainFrom(file: InputFile): String = {
    val scalaSource = file.input.text
      .replaceAll(">>\\w+>>", "")
      .replaceAll("<<\\w+<<", "")

    val actualRanges = findFoldingRangesFor(scalaSource)
    generateOutput(scalaSource, actualRanges)
  }

  private def findFoldingRangesFor(source: String): Seq[FoldingRange] = {
    import collection.JavaConverters._
    val path = registerSource(source)
    foldingRangeProvider.getRangedFor(path).asScala
  }

  private def registerSource(source: String): AbsolutePath = {
    val name = UUID.randomUUID().toString + ".scala"
    val path = AbsolutePath(Paths.get(name))
    buffers.put(path, source)
    path
  }

  private def generateOutput(
      scalaSource: String,
      actualRanges: Seq[FoldingRange]
  ): String = {
    val edits = for {
      range <- actualRanges
      start = new l.Position(range.getStartLine, range.getStartCharacter)
      end = new l.Position(range.getEndLine, range.getEndCharacter)
      edit <- Seq(
        new TextEdit(new l.Range(start, start), s">>${range.getKind}>>"),
        new TextEdit(new l.Range(end, end), s"<<${range.getKind}<<")
      )
    } yield edit

    TextEdits.applyEdits(scalaSource, edits.toList)
  }
}
