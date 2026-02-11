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
 * This is particularly useful for hover, completions and goto definition features between user-authored `.scala.html`
 * templates and their generated `.template.scala` counterparts.
 */

object TwirlAdjustments {

  /**
   * Standard Play Framework imports added to Twirl templates in Play projects.
   */
  private val defaultPlayImports: Seq[String] = Seq(
    "models._", "controllers._", "play.api.i18n._", "views.html._",
    "play.api.templates.PlayMagic._", "play.api.mvc._", "play.api.data._",
  )

  private def playImports(
      originalImports: Seq[String],
      isPlayProject: Boolean,
  ): Seq[String] =
    if (isPlayProject) originalImports ++ defaultPlayImports
    else originalImports

  private def playDI(isPlayProject: Boolean): Seq[String] =
    if (isPlayProject) Seq("@javax.inject.Inject()")
    else Nil

  private def sourceFileFromPath(path: String): File =
    try {
      new File(URI.create(path).getPath)
    } catch {
      case _: Exception => new File(path)
    }

  /**
   * Compiles an in-memory Twirl template into a compiled representation using the Twirl compiler.
   *
   * This method uses a virtual file and a resolved Scala version to invoke `TwirlCompiler.compileVirtual`.
   *
   * @param the virtual file representing the template content
   * @param the full Scala version string (used to resolve compatibility with Twirl)
   * @return the result of compiling the Twirl template
   */
  def getCompiledString(
      file: VirtualFile,
      scalaVersion: String,
      isPlayProject: Boolean,
  ): GeneratedSourceVirtual = {
    val sourceFile = sourceFileFromPath(file.path)
    val sourceDir = Option(sourceFile.getParentFile).getOrElse(new File("."))
    TwirlCompiler
      .compileVirtual(
        content = file.value,
        source = sourceFile,
        sourceDirectory = sourceDir,
        resultType = "play.twirl.api.Html",
        formatterType = "play.twirl.api.HtmlFormat.Appendable",
        additionalImports = playImports(
          TwirlCompiler.defaultImports(scalaVersion),
          isPlayProject,
        ),
        constructorAnnotations = playDI(isPlayProject),
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
   * @param The full text content. Can be either the Twirl Source or the Compiled Twirl File
   * @param The character offset within the text (0-based).
   * @return A `Position` object representing the line and column corresponding to the given index.
   */
  private def getPositionFromIndex(text: String, index: Int): Position = {
    val lines = text.substring(0, index).split("\n", -1)
    new Position(lines.length - 1, lines.last.length)
  }

  /**
   * Converts an LSP `Position` (0 based - line number and character offset) into a character index.
   *
   * @param The full text content. Can be either the Twirl Source or the Compiled Twirl File
   * @param The LSP `Position` to convert (line and character).
   * @return The absolute character index in the string corresponding to the position.
   */
  private def getIndexFromPosition(text: String, pos: Position): Int = {
    val lines = text.split('\n')
    lines
      .take(pos.getLine)
      .map(_.length + 1)
      .sum + pos.getCharacter
  }

  val pattern: Regex = """(\d+)->(\d+)""".r

  /**
   * Extracts a positional mapping matrix from the compiled Twirl template.
   *
   * This method parses those mappings and builds a matrix of (original, generated) index pairs.
   * The mapping is later used for position translation between source and compiled files.
   *
   * @param The compiled Twirl template content as a string
   * @return An array of tuples representing (originalIndex, generatedIndex) pairs
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
      isPlayProject: Boolean = false,
  ): (VirtualFile, Position => Position, AdjustLspData) = {

    val originalTwirl = twirlFile.value
    val compiledSource =
      getCompiledString(twirlFile, rawScalaVersion, isPlayProject)
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
      if (idx <= 0 || matrix.isEmpty) {
        return originalPos
      } else {
        val (origBase, genBase) = matrix(idx - 1)
        val mappedIndex = genBase + (originalIndex - origBase)
        getPositionFromIndex(compiledTwirl, mappedIndex)
      }
    }

    /**
     * Maps a Position in the compiled Scala output back to the original
     */
    def reverseMapPosition(compiledPos: Position): Position = {
      val compiledIndex = getIndexFromPosition(compiledTwirl, compiledPos)
      val mappedIndex = compiledSource.mapPosition(compiledIndex)
      getPositionFromIndex(originalTwirl, mappedIndex)
    }

    (
      newVirtualFile,
      mapPosition,
      AdjustedLspData.create(reverseMapPosition),
    )
  }
}
