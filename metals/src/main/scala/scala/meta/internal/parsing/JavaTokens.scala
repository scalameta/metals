package scala.meta.internal.parsing

import scala.collection.compat.immutable.ArraySeq
import scala.collection.mutable.ArrayBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.inputs.Input
import scala.meta.inputs.Input.File
import scala.meta.inputs.Input.VirtualFile

import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.compiler.IScanner
import org.eclipse.jdt.core.compiler.ITerminalSymbols

object JavaTokens {

  /**
   * Best effort translation of java tokens to the scalameta tokens, should work for most purposes.
   *
   * @param source to tokenize
   * @return tokenized source
   */
  def tokenize(source: Input): Option[Seq[JavaToken]] = Try {

    val scanner = ToolFactory.createScanner(
      /*tokenizeComments = */ true, /*tokenizeWhiteSpace = */ true,
      /*assertMode = */ false, /*recordLineSeparator = */ true
    )
    scanner.setSource(source.chars)

    val buffer = new ArrayBuffer[JavaToken]()

    def loop(token: Int): Unit = {
      if (token != ITerminalSymbols.TokenNameEOF) {
        mapToken(token, scanner, source, buffer)
        loop(scanner.getNextToken())
      }
    }

    loop(scanner.getNextToken())
    ArraySeq(buffer: _*)
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

  private def mapToken(
      i: Int,
      scanner: IScanner,
      source: Input,
      buffer: ArrayBuffer[JavaToken]
  ): Unit = {
    val start = scanner.getCurrentTokenStartPosition()
    val end = scanner.getCurrentTokenStartPosition()

    def token(txt: String, isLF: Boolean = false) = JavaToken(
      i,
      txt,
      start,
      end,
      source,
      isLF = false
    )
    i match {
      case ITerminalSymbols.TokenNameWHITESPACE =>
        scanner.getCurrentTokenSource().foreach {
          case '\n' => buffer += token("\n", isLF = true)
          case '\r' => buffer += token("\r")
          case '\f' => buffer += token("\f")
          case '\t' => buffer += token("\t")
          case ' ' => buffer += token(" ")
          case ch => buffer += token(ch.toString())
        }
      case _ =>
        buffer += token(scanner.getCurrentTokenSource().mkString)
    }
  }

}
