package scala.meta.internal.metals

import scala.meta.inputs.Input
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.io.AbsolutePath
import scala.io.Source

import org.eclipse.{lsp4j => l}
import java.io.File

final case class SourceMapper(
    buildTargets: BuildTargets,
    buffers: Buffers,
) {
  def mappedFrom(path: AbsolutePath): Option[AbsolutePath] =
    buildTargets.mappedFrom(path)

  def mappedTo(path: AbsolutePath): Option[AbsolutePath] =
    buildTargets.mappedTo(path).map(_.path)
  def mappedLineForServer(path: AbsolutePath, line: Int): Int =
    buildTargets.mappedLineForServer(path, line).getOrElse(line)
  def mappedLineForClient(path: AbsolutePath, line: Int): Int =
    buildTargets.mappedLineForClient(path, line).getOrElse(line)

  def twirlMapper(
      twirlPath: Input.VirtualFile
  ): (Input.VirtualFile, l.Position => l.Position, AdjustLspData) = {

    val compiledTwirl =
      Source
        .fromFile(
          new File(
            "/home/ajafri/scala/play-test/target/scala-3.7.1/twirl/main/html/example.template.scala"
          )
        )
        .mkString

    val newVirtualFile = twirlPath.copy(value = compiledTwirl)

    val pattern = """(\d+)->(\d+)""".r
    val number_matching =
      pattern.findAllIn(compiledTwirl).toList

    val chars = number_matching.take(number_matching.length / 2)
    val lines = number_matching.drop(number_matching.length / 2)

    // Map[Int, Int](1 -> 15, 2 -> 20, 3 -> 21)  SourceFile -> CompiledFile
    val lineMap: Map[Int, Int] = lines.map { s =>
      val parts = s.split("->")
      val a = parts(0).toInt
      val b = parts(1).toInt
      b - 1 -> a
    }.toMap

    val charMap: Map[Int, Int] = chars.map { s =>
      val parts = s.split("->")
      val a = parts(0).toInt
      val b = parts(1).toInt
      b -> a
    }.toMap

    def findCharPosition(char_pos: Int): Int = {
      var index = 0
      var pos = 0
      while (index < char_pos) {
        if (compiledTwirl(index) == '\n') {
          pos = 0
        }
        index += 1
        pos += 1
      }
      pos
    }

    (
      newVirtualFile,
      pos =>
        new l.Position(
          lineMap(pos.getLine()) - 1,
          findCharPosition(charMap(pos.getCharacter())),
        ),
      AdjustedLspData.create(pos =>
        new l.Position(
          lineMap(pos.getLine()) - 1,
          findCharPosition(charMap(pos.getCharacter())),
        )
      ),
    )
  }

  def pcMapping(
      path: AbsolutePath,
      scalaVersion: String,
  ): (Input.VirtualFile, l.Position => l.Position, AdjustLspData) = {

    def input = path.toInputFromBuffers(buffers)
    def default = {
      val viaBuildTargets =
        buildTargets.mappedTo(path).map(_.update(input.value))
      viaBuildTargets.getOrElse(
        (input, identity[l.Position] _, AdjustedLspData.default)
      )
    }

    val forScripts =
      if (path.isSbt) {
        buildTargets
          .sbtAutoImports(path)
          .map(
            SbtBuildTool.sbtInputPosAdjustment(input, _)
          )
      } else if (
        path.isWorksheet && ScalaVersions.isScala3Version(scalaVersion)
      ) {
        WorksheetProvider.worksheetScala3Adjustments(input)
      } else if (path.isTwirlTemplate) {
        Some(twirlMapper(input))
      } else None

    forScripts.getOrElse(default)
  }
}
