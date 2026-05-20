package scala.meta.internal.metals

import java.io.File
import java.net.URI

import scala.io.Codec
import scala.util.matching.Regex

import scala.meta.inputs.Input.VirtualFile

import org.eclipse.lsp4j.Position
import play.twirl.compiler.GeneratedSourceVirtual
import play.twirl.compiler.TwirlCompiler

/**
 * A utility object for adjusting and mapping positions between Twirl templates and their compiled Scala output.
 *
 * This is particularly useful for hover, completions and goto definition features between user-authored
 * Twirl templates (`.scala.html`, `.scala.js`, `.scala.xml`, `.scala.txt`) and their generated `.template.scala` counterparts.
 */

object TwirlAdjustments {

  private val defaultPlayImports: Seq[String] = Seq(
    "models._", "controllers._", "play.api.i18n._", "views.html._",
    "play.api.templates.PlayMagic._", "play.api.mvc._", "play.api.data._",
  )

  private def playImports(
      originalImports: Seq[String],
      playVersion: Option[String],
  ): Seq[String] =
    if (playVersion.isDefined) originalImports ++ defaultPlayImports
    else originalImports

  private def playDI(playVersion: Option[String]): Seq[String] =
    if (playVersion.isDefined) Seq("@javax.inject.Inject()")
    else Nil

  private def sourceFileFromPath(path: String): File =
    try {
      new File(URI.create(path).getPath)
    } catch {
      case _: Exception => new File(path)
    }

  private def twirlFormat(path: String): (String, String) =
    if (path.endsWith(".scala.js"))
      (
        "play.twirl.api.JavaScript",
        "play.twirl.api.JavaScriptFormat.Appendable",
      )
    else if (path.endsWith(".scala.xml"))
      ("play.twirl.api.Xml", "play.twirl.api.XmlFormat.Appendable")
    else if (path.endsWith(".scala.txt"))
      ("play.twirl.api.Txt", "play.twirl.api.TxtFormat.Appendable")
    else
      ("play.twirl.api.Html", "play.twirl.api.HtmlFormat.Appendable")

  /**
   * Compiles an in-memory Twirl template into a compiled representation using the Twirl compiler.
   *
   * This method uses a virtual file and a resolved Scala version to invoke `TwirlCompiler.compileVirtual`.
   *
   * @param file the virtual file representing the template content
   * @param scalaVersion the full Scala version string (used to resolve compatibility with Twirl)
   * @param playVersion the Play Framework version if this is a Play project, affects imports and DI annotations
   * @return the result of compiling the Twirl template
   */
  def getCompiledString(
      file: VirtualFile,
      scalaVersion: String,
      playVersion: Option[String],
  ): GeneratedSourceVirtual = {
    val sourceFile = sourceFileFromPath(file.path)
    val sourceDir = Option(sourceFile.getParentFile).getOrElse(new File("."))
    val (resultType, formatterType) = twirlFormat(file.path)
    TwirlCompiler
      .compileVirtual(
        content = file.value,
        source = sourceFile,
        sourceDirectory = sourceDir,
        resultType = resultType,
        formatterType = formatterType,
        additionalImports = playImports(
          TwirlCompiler.defaultImports(scalaVersion),
          playVersion,
        ),
        constructorAnnotations = playDI(playVersion),
        codec = Codec(
          scala.util.Properties.sourceEncoding
        ),
        scalaVersion = Some(scalaVersion),
        inclusiveDot = true,
      )
  }

  /**
   * Converts a character offset (index) in a string to an LSP `Position` (0 based - line number and character offset).
   *
   * @param text the full text content, can be either the Twirl source or the compiled Twirl file
   * @param index the character offset within the text (0-based)
   * @return a `Position` object representing the line and column corresponding to the given index
   */
  private def getPositionFromIndex(text: String, index: Int): Position = {
    val lines = text.substring(0, index).split("\n", -1)
    new Position(lines.length - 1, lines.last.length)
  }

  /**
   * Converts an LSP `Position` (0 based - line number and character offset) into a character index.
   *
   * @param text the full text content, can be either the Twirl source or the compiled Twirl file
   * @param pos the LSP `Position` to convert (line and character)
   * @return the absolute character index in the string corresponding to the position
   */
  private def getIndexFromPosition(text: String, pos: Position): Int = {
    val lines = text.split("\n", -1)
    lines
      .take(pos.getLine)
      .map(_.length + 1)
      .sum + pos.getCharacter
  }

  private val pattern: Regex = """(\d+)->(\d+)""".r

  /**
   * Extracts a positional mapping matrix from the compiled Twirl template.
   *
   * This method parses those mappings and builds a matrix of (original, generated) index pairs.
   * The mapping is later used for position translation between source and compiled files.
   *
   * @param compiledTwirl the compiled Twirl template content as a string
   * @return an array of tuples representing (originalIndex, generatedIndex) pairs
   */
  private def getMatrix(compiledTwirl: String): Array[(Int, Int)] = {
    val numberMatching =
      pattern.findAllIn(compiledTwirl).toArray
    val chars = numberMatching.take(numberMatching.length / 2)
    chars.map { char =>
      val parts = char.split("->")
      val a = parts(0).toInt
      val b = parts(1).toInt
      (b, a)
    }
  }

  /**
   * Maps positions between original Twirl template and compiled Scala output.
   *
   * Returns a tuple of:
   *   - The compiled virtual file,
   *   - A function to map original Twirl positions -> compiled Scala positions,
   *   - An AdjustedLspData instance for reverse mapping compiled Scala -> Twirl positions.
   */
  def apply(
      twirlFile: VirtualFile,
      rawScalaVersion: String,
      playVersion: Option[String] = None,
  ): (VirtualFile, Position => Position, AdjustLspData) = {

    val originalTwirl = twirlFile.value
    val compiledSource =
      getCompiledString(twirlFile, rawScalaVersion, playVersion)
    val compiledTwirl = compiledSource.content
    val newVirtualFile = twirlFile.copy(value = compiledTwirl)
    val matrix: Array[(Int, Int)] = getMatrix(compiledTwirl)

    /**
     * Maps a Position in the original Twirl template to the corresponding
     * Position in the compiled Scala output
     */
    def mapPosition(originalPos: Position): Position = {
      val originalIndex = getIndexFromPosition(originalTwirl, originalPos)
      val idx = matrix.indexWhere(_._1 >= originalIndex)
      if (matrix.isEmpty) {
        return originalPos
      } else {
        val baseIdx =
          if (idx == -1) matrix.length - 1
          else if (idx == 0) 0
          else idx - 1
        val (origBase, genBase) = matrix(baseIdx)
        val mappedIndex = math.min(
          compiledTwirl.length,
          math.max(0, genBase + (originalIndex - origBase)),
        )
        getPositionFromIndex(compiledTwirl, mappedIndex)
      }
    }

    /**
     * Maps a Position in the compiled Scala output back to the original
     */
    def reverseMapPosition(compiledPos: Position): Position = {
      val compiledIndex = getIndexFromPosition(compiledTwirl, compiledPos)
      val mappedIndex = math.min(
        originalTwirl.length,
        math.max(0, compiledSource.mapPosition(compiledIndex)),
      )
      getPositionFromIndex(originalTwirl, mappedIndex)
    }

    (
      newVirtualFile,
      mapPosition,
      AdjustedLspData.create(reverseMapPosition),
    )
  }
}
