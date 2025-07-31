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
        scalaVersion = Some(resolveVersion(scalaVersion)),
        inclusiveDot = false,
      )

  private def getPositionFromIndex(text: String, index: Int): Position = {
    val lines = text.substring(0, index).split('\n')
    new Position(lines.length - 1, lines.last.length)
  }

  private def getIndexFromPosition(text: String, pos: Position): Int = {
    val lines = text.split('\n')

    lines
      .take(pos.getLine)
      .map(_.length + 1)
      .sum + pos.getCharacter
  }

  def twirlMapper(
      twirlFile: VirtualFile,
      rawScalaVersion: String,
  ): (VirtualFile, Position => Position, AdjustLspData) = {

    val originalTwirl = twirlFile.value
    val compiledSource = getCompiledString(twirlFile, rawScalaVersion)
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
      (b, a)
    }

    def mapPosition(originalPos: Position): Position = {
      val originalIndex = getIndexFromPosition(originalTwirl, originalPos)
      val mappedIndex = matrix.indexWhere { case (orig, _) =>
        orig > originalIndex
      } match {
        case 0 => 0
        case idx if idx > 0 =>
          val (origBase, genBase) = matrix(idx - 1)
          genBase + (originalIndex - origBase)
        case _ =>
          val (origBase, genBase) = matrix.last
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
