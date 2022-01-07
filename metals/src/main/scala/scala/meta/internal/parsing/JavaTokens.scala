package scala.meta.internal.parsing

import scala.collection.mutable.ArrayBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.inputs.Input
import scala.meta.inputs.Input.File
import scala.meta.inputs.Input.VirtualFile
import scala.meta.inputs.Position

import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.compiler.ITerminalSymbols

case class JavaToken(
    id: Int,
    text: String,
    start: Int,
    end: Int,
    input: Input,
    isLF: Boolean = false
) {
  lazy val pos: Position = Position.Range(input, start, end)
}

object JavaTokens {

  /**
   * Best effort translation of java tokens to the scalameta tokens, should work for most purposes.
   *
   * @param source to tokenize
   * @return tokenized source
   */
  def tokenize(source: Input): Option[Array[JavaToken]] = Try {

    val scanner = ToolFactory.createScanner(
      /*tokenizeComments = */ true, /*tokenizeWhiteSpace = */ true,
      /*assertMode = */ false, /*recordLineSeparator = */ true
    )
    scanner.setSource(source.chars)

    val buffer = new ArrayBuffer[JavaToken]()

    def loop(token: Int): Unit = {
      if (token != ITerminalSymbols.TokenNameEOF) {
        val start = scanner.getCurrentTokenStartPosition()
        val end = scanner.getCurrentTokenStartPosition()

        def createToken(txt: String, isLF: Boolean = false) = JavaToken(
          token,
          txt,
          start,
          end,
          source,
          isLF = false
        )
        token match {
          case ITerminalSymbols.TokenNameWHITESPACE =>
            scanner.getCurrentTokenSource().foreach {
              case '\n' => buffer += createToken("\n", isLF = true)
              case '\r' => buffer += createToken("\r")
              case '\f' => buffer += createToken("\f")
              case '\t' => buffer += createToken("\t")
              case ' ' => buffer += createToken(" ")
              case ch => buffer += createToken(ch.toString())
            }
          case _ =>
            buffer += createToken(scanner.getCurrentTokenSource().mkString)
        }
        loop(scanner.getNextToken())
      }
    }

    loop(scanner.getNextToken())
    buffer.toArray
  } match {
    case Failure(exception) =>
      source match {
        case VirtualFile(path, _) =>
          scribe.warn(s"Cannot tokenize Java file $path", exception)
        case File(path, _) =>
          scribe.warn(s"Cannot tokenize Java file $path", exception)
        case _ =>
      }
      None
    case Success(value) => Some(value)
  }
}
