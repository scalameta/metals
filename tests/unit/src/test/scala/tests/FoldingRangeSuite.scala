package tests

import java.nio.file.Paths
import java.util.UUID

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.parsing.FoldingRangeProvider
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}
import tests.BuildInfo.testResourceDirectory

abstract class FoldingRangeSuite(
    scalaVersion: String,
    directory: String
) extends DirectoryExpectSuite(s"$directory/expect") {
  private val buffers = Buffers()
  private val buildTargets = new BuildTargets(_ => None)
  private val selector =
    new ScalaVersionSelector(
      () => UserConfiguration(fallbackScalaVersion = Some(scalaVersion)),
      buildTargets
    )
  private val trees = new Trees(buildTargets, buffers, selector)
  private val foldingRangeProvider = new FoldingRangeProvider(trees, buffers)

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
    val scalaSource = file.input.text

    val actualRanges = findFoldingRangesFor(scalaSource)
    val edits = RangesTextEdits.fromFoldingRanges(actualRanges)
    TextEdits.applyEdits(scalaSource, edits)
  }

  private def findFoldingRangesFor(
      source: String
  ): java.util.List[l.FoldingRange] = {
    val path = registerSource(source)
    foldingRangeProvider.getRangedFor(path)
  }

  private def registerSource(source: String): AbsolutePath = {
    val name = UUID.randomUUID().toString + ".scala"
    val path = AbsolutePath(Paths.get(name))
    buffers.put(path, source)
    path
  }
}

class FoldingRangeScala2Suite
    extends FoldingRangeSuite(V.scala213, "foldingRange")
class FoldingRangeScala3Suite
    extends FoldingRangeSuite(V.scala3, "foldingRange-scala3")
