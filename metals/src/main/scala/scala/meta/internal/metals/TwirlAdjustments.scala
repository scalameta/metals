package scala.meta.internal.metals

import java.io.File

import scala.io.Codec

import scala.meta.inputs.Input.VirtualFile

import org.eclipse.lsp4j.Position
import play.twirl.compiler.TwirlCompiler

object TwirlAdjustments {

  private def resolveVersion(scalaVersion: String): String = {
    val base = scalaVersion.split('-')(0)
    base match {
      case v if v.startsWith("2") => v.split('.').take(2).mkString(".")
      case v => v
    }
  }

  private def getCompiledString(file: VirtualFile, scalaVersion: String) =
    TwirlCompiler
      .compileVirtual(
        content = file.value,
        source = new File(
          "foo/bar/example.scala.html"
        ), // I don't think source etc is needed
        sourceDirectory = new File("foo/bar"),
        resultType = "play.twirl.api.Html",
        formatterType = "play.twirl.api.HtmlFormat.Appendable",
        additionalImports = TwirlCompiler.defaultImports(scalaVersion),
        constructorAnnotations = Nil,
        codec = Codec(scala.util.Properties.sourceEncoding),
        scalaVersion = Some(scalaVersion),
        inclusiveDot = false,
      )

  private def getPositionFromIndex(text: String, index: Int): Position = {
    var row = 0
    var lastNewline = -1
    for (i <- 0 until index) {
      if (text.charAt(i) == '\n') {
        row += 1
        lastNewline = i
      }
    }
    new Position(row, index - (lastNewline + 1))
  }

  private def getIndexFromPosition(text: String, pos: Position): Int = {
    var row = 0
    var i = 0
    while (row < pos.getLine && i < text.length) {
      if (text.charAt(i) == '\n') row += 1
      i += 1
    }
    i + pos.getCharacter
  }

  def twirlMapper(
      twirlFile: VirtualFile,
      rawScalaVersion: String,
  ): (VirtualFile, Position => Position, AdjustLspData) = {

    val originalTwirl = twirlFile.value
    val scalaVersion = resolveVersion(rawScalaVersion)
    val compiledSource = getCompiledString(twirlFile, scalaVersion)
    val compiledTwirl = compiledSource.content
    println(compiledTwirl)
    val newVirtualFile = twirlFile.copy(value = compiledTwirl)

    val pattern = """(\d+)->(\d+)""".r
    val number_matching =
      pattern.findAllIn(compiledTwirl).toVector

    val chars = number_matching.take(number_matching.length / 2)

    val matrix = chars.map { char =>
      val parts = char.split("->")
      val a = parts(0).toInt
      val b = parts(1).toInt
      (a, b)
    }

    val reverseMatrix = matrix.map(n => (n._2, n._1))

    def mapPosition(originalPos: Position): Position = {
      val originalIndex = getIndexFromPosition(originalTwirl, originalPos)
      val mappedIndex = reverseMatrix.indexWhere { case (orig, _) =>
        orig > originalIndex
      } match {
        case 0 => 0
        case idx if idx > 0 =>
          val (origBase, genBase) = reverseMatrix(idx - 1)
          genBase + (originalIndex - origBase)
        case _ =>
          val (origBase, genBase) = reverseMatrix.last
          genBase + (originalIndex - origBase)
      }
      getPositionFromIndex(compiledTwirl, mappedIndex)
    }

    def reverseMapPosition(compiledPos: Position): Position = {
      val compiledIndex = getIndexFromPosition(compiledTwirl, compiledPos)

      val pos_tuple = compiledSource.mapPosition(compiledIndex)

      getPositionFromIndex(originalTwirl, pos_tuple)
    }

    (
      newVirtualFile,
      mapPosition,
      AdjustedLspData.create(reverseMapPosition),
    )
  }
}
