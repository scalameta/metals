package tests.play

import tests.BaseSuite
import java.io.File

case class Position(linePosition: Int, characterPosition: Int)

class TwirlSuite extends BaseSuite {

  val cursorPosition = Position(0, 0)

  val inputFile =
    "/home/ajafri/scala/play-test/target/scala-3.7.1/twirl/main/html/example.template.scala"

  val compiledTwirl =
    scala.io.Source.fromFile(new File(inputFile)).mkString

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

  def findCharPosition(char_pos: Int): Position = {
    var index = 0
    var pos = 0
    var lines = 0
    while (index < char_pos) {
      if (compiledTwirl(index) == '\n') {
        pos = 0
        lines += 1
      }
      index += 1
      pos += 1
    }
    Position(lines, pos)
  }

  test("twirl-mapping") {
    println("Line Position -> " + (lineMap(cursorPosition.linePosition) - 1))
    println(
      "Character Position -> " + findCharPosition(
        charMap(cursorPosition.characterPosition)
      ).characterPosition
    )
    assert(5 == 5)
  }

}
