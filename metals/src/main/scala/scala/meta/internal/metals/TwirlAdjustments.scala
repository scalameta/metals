package scala.meta.internal.metals

import java.io.File
import scala.meta.inputs.Input.VirtualFile
import scala.io.Source
import org.eclipse.lsp4j.Position
import scala.collection.immutable.ListMap

object TwirlAdjustments {

  def twirlMapper(
      twirlFile: VirtualFile,
      rawScalaVersion: String,
  ): (VirtualFile, Position => Position, AdjustLspData) = {

    val base = rawScalaVersion.split('-')(0)

    type AnchorLength = Int

    val originalTwirl = twirlFile.value

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

    val compiledTwirl = Source.fromFile(new File(compiledTwirlPath)).mkString

    val newVirtualFile = twirlFile.copy(value = compiledTwirl)

    val anchorRegex = raw"/\*\d+\.\d+\*/".r
    val pattern = """(\d+)->(\d+)""".r
    val number_matching =
      pattern.findAllIn(compiledTwirl).toList

    val lengthOfAnchors: Iterator[Int] =
      anchorRegex.findAllIn(compiledTwirl).map(_.length)

    val chars = number_matching.take(number_matching.length / 2)
    val lines = number_matching.drop(number_matching.length / 2)

    def getCharAtPosition(pos: Position): Char = {
      val lines = compiledTwirl.split("\n", -1) // preserve trailing empty lines
      val line = pos.getLine()
      val char = pos.getCharacter()

      lines(line).charAt(char)
    }

    def findCharPosition(char_pos: Int, sourceFile: String): Int = {
      var index = 0
      var pos = 0
      while (index < char_pos) {
        index += 1
        pos += 1
        if (sourceFile(index) == '\n') {
          pos = 0
        }
      }
      pos
    }

    // Map[Int, Int](1 -> 15, 2 -> 20, 3 -> 21)  SourceFile -> CompiledFile
    val lineMap: ListMap[Int, Int] = ListMap.from {
      lines.map { s =>
        val parts = s.split("->")
        val a = parts(0).toInt
        val b = parts(1).toInt
        (b - 1) -> (a - 1)
      }
    }

    val charMap: ListMap[Int, (Int, AnchorLength)] = ListMap.from {
      chars.map { s =>
        val parts = s.split("->")
        val a = findCharPosition(parts(0).toInt, compiledTwirl)
        val b = findCharPosition(parts(1).toInt, originalTwirl)
        b -> (a - 1, lengthOfAnchors.next)
      }
    }

    println(charMap)

    def positionMapping(pos: Position): Position = {
      val charPos = pos.getCharacter()
      val linePos = pos.getLine()

      val newPosition =
        new Position(
          lineMap(linePos),
          charMap(charPos)._1,
        )

      pprint.log("Old Position => " + pos + "\n New Position => " + newPosition)

      pprint.log(getCharAtPosition(newPosition))

      println(compiledTwirl(charMap(charPos)._1))

      newPosition
    }

    def adjustLSPMapping(pos: Position): Position = {
      val charPos = pos.getCharacter()
      val linePos = pos.getLine()

      val adjustedPosition =
        // TODO - This needs to be changed from here
        new Position(
          lineMap(linePos),
          charMap(charPos)._1,
        )
      adjustedPosition
    }

    (
      newVirtualFile,
      positionMapping,
      AdjustedLspData.create(adjustLSPMapping),
    )
  }
}
