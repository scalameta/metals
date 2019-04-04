package tests

import java.nio.file.Paths
import java.util.UUID
import org.eclipse.lsp4j.FoldingRange
import tests.MutableText.Insert
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.FoldingRangeProvider
import scala.meta.io.AbsolutePath

object FoldingRangeSuite extends CustomInputExpectSuite("foldingRange") {
  private val buffers = Buffers()
  private val trees = TestingTrees(buffers)

  private val foldingRangeProvider =
    new FoldingRangeProvider(trees, foldOnlyLines = false)

  override protected def obtainFrom(file: InputFile): String = {
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
    val mutableText = MutableText(scalaSource)

    val insertions = for {
      range <- actualRanges
      insertion <- Seq(opening(range), closing(range))
    } yield insertion

    mutableText.updateWith(insertions)
    mutableText.toString
  }

  private def opening(range: FoldingRange): Insert =
    Insert(
      range.getStartLine,
      range.getStartCharacter,
      s">>${range.getKind}>>"
    )

  private def closing(range: FoldingRange): Insert =
    Insert(
      range.getEndLine,
      range.getEndCharacter,
      s"<<${range.getKind}<<"
    )
}
