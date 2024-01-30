package scala.meta.internal.parsing

import scala.collection.concurrent.TrieMap
import scala.reflect.ClassTag

import scala.meta._
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.pc.StandardReport
import scala.meta.io.AbsolutePath
import scala.meta.parsers.Parse
import scala.meta.parsers.ParseException
import scala.meta.parsers.Parsed
import scala.meta.pc.ReportContext
import scala.meta.tokens.Tokens

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.{lsp4j => l}

/**
 * Manages parsing of Scala source files into Scalameta syntax trees.
 *
 * - provides the latest good Scalameta tree for a given source file
 * similar as `Buffers` provides the current text content.
 * - produces diagnostics for syntax errors.
 */
final class Trees(
    buffers: Buffers,
    scalaVersionSelector: ScalaVersionSelector,
)(implicit reports: ReportContext) {

  private val trees = TrieMap.empty[AbsolutePath, Tree]

  def get(path: AbsolutePath): Option[Tree] =
    trees.get(path).orElse {
      // Fallback to parse without caching result.
      parse(path, scalaVersionSelector.getDialect(path)).flatMap(_.toOption)
    }

  def didClose(fileUri: AbsolutePath): Unit = {
    trees.remove(fileUri)
  }

  private def enclosedChildren(
      children: List[Tree],
      pos: Position,
  ): List[Tree] = {
    children
      .filter { child =>
        child.pos.start <= pos.start && pos.start <= child.pos.end
      }
  }

  /**
   * Find last tree matching T that encloses the position.
   *
   * @param source    source to load the tree for
   * @param lspPos    cursor position
   * @param predicate predicate which T must fulfill
   * @return found tree node of type T or None
   */
  def findLastEnclosingAt[T <: Tree: ClassTag](
      source: AbsolutePath,
      lspPos: l.Position,
      predicate: T => Boolean = (_: T) => true,
  ): Option[T] = {

    def loop(t: Tree, pos: Position): Option[T] = {
      t match {
        case t: T =>
          enclosedChildren(t.children, pos)
            .flatMap(loop(_, pos).toList)
            .headOption
            .orElse(if (predicate(t)) Some(t) else None)
        case other =>
          enclosedChildren(other.children, pos)
            .flatMap(loop(_, pos).toList)
            .headOption
      }
    }

    for {
      tree <- get(source)
      pos <- lspPos.toMeta(tree.pos.input)
      lastEnc <- loop(tree, pos)
    } yield lastEnc

  }

  /**
   * Parse file at the given path and return a list of errors if there are any.
   *
   * @param path file to parse
   * @return list of errors if the file failed to parse
   */
  def didChange(path: AbsolutePath): List[Diagnostic] = {
    val dialect = scalaVersionSelector.getDialect(path)
    parse(path, dialect) match {
      case Some(parsed) =>
        parsed match {
          case Parsed.Error(pos, message, _) =>
            List(
              new Diagnostic(
                pos.toLsp,
                message,
                DiagnosticSeverity.Error,
                "scalameta",
              )
            )
          case Parsed.Success(tree) =>
            trees(path) = tree
            List.empty
          case _ =>
            List.empty
        }
      case _ => List.empty
    }
  }

  def tokenized(path: AbsolutePath): Option[Tokens] = {
    for {
      sourceText <- buffers.get(path)
      virtualFile = Input.VirtualFile(path.toURI.toString(), sourceText)
      tokens <- tokenized(virtualFile).toOption
    } yield tokens
  }

  def tokenized(input: inputs.Input.VirtualFile): Tokenized = {
    scalaVersionSelector
      .getDialect(input.path.toAbsolutePath)(input)
      .tokenize
  }

  private def parse(
      path: AbsolutePath,
      dialect: Dialect,
  ): Option[Parsed[Tree]] =
    for {
      text <- buffers.get(path).orElse(path.readTextOpt)
    } yield try {
      val skipFistShebang =
        if (text.startsWith("#!")) text.replaceFirst("#!", "//") else text
      val input = Input.VirtualFile(path.toURI.toString(), skipFistShebang)
      if (path.isAmmoniteScript || path.isMill) {
        val ammoniteInput = Input.Ammonite(input)
        dialect(ammoniteInput).parse(Parse.parseAmmonite)
      } else {
        dialect(input).parse[Source]
      }
    } catch {
      // if the parsers breaks we should not throw the exception further
      case _: StackOverflowError =>
        val newPathCopy = reports.unsanitized.create(
          StandardReport(
            s"stackoverflow_${path.filename}",
            text,
            s"Stack overflow in ${path.filename}",
            path = Some(path.toURI.toString()),
          )
        )
        val message =
          s"Could not parse $path, saved the current snapshot to ${newPathCopy}"
        scribe.warn(message)
        Parsed.Error(
          Position.None,
          message,
          new ParseException(Position.None, message),
        )
    }

}

object Trees {

  /* Tokenizing works perfectly fine with 212 dialect as long as we are only
   * interested in having stable results. This is not the case for parsing.
   */
  val defaultTokenizerDialect: Dialect = scala.meta.dialects.Scala213

}
