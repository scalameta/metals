package scala.meta.internal.metals

import java.io.File
import scala.meta.inputs.Input.VirtualFile
import scala.io.Source
import org.eclipse.lsp4j.Position

object TwirlAdjustments {

  def twirlMapper(
      twirlFile: VirtualFile,
      rawScalaVersion: String,
  ): (VirtualFile, Position => Position, AdjustLspData) = {

    val base = rawScalaVersion.split('-')(0)

    val scalaVersion = base match {
      case v if v.startsWith("2") => v.split('.').take(2).mkString(".")
      case v => v
    }

    val compiledTwirlPath = twirlFile.path
      .stripPrefix("file://")
      .replace(
        "src/main/twirl",
        s"target/scala-${scalaVersion}/twirl/main/html",
      )
      .replace(".scala.html", ".template.scala")

    println(compiledTwirlPath)

    val compiledTwirl = Source.fromFile(new File(compiledTwirlPath)).mkString

    val newVirtualFile = twirlFile.copy(value = compiledTwirl)

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
      while (index <= char_pos) {
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
        new Position(
          lineMap(pos.getLine()) - 1,
          findCharPosition(charMap(pos.getCharacter())),
        ),
      AdjustedLspData.create(pos =>
        new Position(
          lineMap(pos.getLine()) - 1,
          findCharPosition(charMap(pos.getCharacter())),
        )
      ),
    )
  }
}
