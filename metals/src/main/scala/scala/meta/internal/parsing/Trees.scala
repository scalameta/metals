package scala.meta.internal.parsing

import java.util.Optional

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.reflect.ClassTag

import scala.meta._
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.io.AbsolutePath
import scala.meta.parsers.ParseException
import scala.meta.parsers.Parsed
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
    val scalaVersionSelector: ScalaVersionSelector,
)(implicit reports: ReportContext) {

  private val trees = TrieMap.empty[AbsolutePath, Tree]
  private val tokenized = TrieMap.empty[AbsolutePath, Tokens]

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

  def packageStatementsAtPosition(
      source: AbsolutePath,
      lspPos: l.Position,
  ): Option[List[String]] = {

    def loop(
        t: Tree,
        pos: Position,
        acc: List[String],
    ): Option[List[String]] = {
      t match {
        case t: Pkg =>
          val enclosed = enclosedChildren(t.children, pos)
          enclosed
            .map(loop(_, pos, acc :+ t.ref.toString()))
            .headOption
            .flatten
        case other =>
          val enclosed = enclosedChildren(other.children, pos)
          if (enclosed.isEmpty) Some(acc)
          else enclosed.flatMap(loop(_, pos, acc)).headOption
      }
    }

    for {
      tree <- get(source)
      pos <- lspPos.toMeta(tree.pos.input)
      lastEnc <- loop(tree, pos, Nil)
    } yield lastEnc
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
   * Find all trees matching T that overlap with the specified range.
   *
   * Does not recurse into subtrees of a tree that itself matches.
   *
   * @param source    source to load the tree for
   * @param lspRange  selection range
   * @return found tree nodes of type T (in traversal order)
   */
  def findAllInRange[T <: Tree: ClassTag](
      source: AbsolutePath,
      lspRange: l.Range,
  ): Seq[T] = {
    get(source) match {
      case None => Nil
      case Some(root) =>
        val matches = Seq.newBuilder[T]
        val toVisit = mutable.Stack[Tree](root)
        while (!toVisit.isEmpty) {

          val tree = toVisit.pop()
          if (lspRange.overlapsWith(tree.pos.toLsp)) {
            tree match {
              case t: T =>
                matches += t
              case other =>
                // reverse to preserve traversal order
                toVisit.pushAll(other.children.reverse)
            }
          }
        }
        matches.result()
    }
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
    tokenized
      .get(path)
      .orElse {
        trees.get(path).map(_.tokens)
      }
      .orElse(tokenize(path))
  }

  private def tokenize(path: AbsolutePath): Option[Tokens] = {
    for {
      sourceText <- buffers.get(path)
      dialect = scalaVersionSelector.getDialect(path)
      tokens <- sourceText.safeTokenize(dialect).toOption
    } yield tokens
  }

  private def parse(
      path: AbsolutePath,
      dialect: Dialect,
  ): Option[Parsed[Tree]] = {
    for {
      text <- buffers.get(path).orElse(path.readTextOpt)
    } yield
      try {
        val skipFistShebang =
          if (text.startsWith("#!")) text.replaceFirst("#!", "//") else text
        val input = Input.VirtualFile(path.toURI.toString(), skipFistShebang)
        val possiblyParsed = if (path.isMill) {
          val ammoniteInput = Input.Ammonite(input)
          ammoniteInput.safeParse[MultiSource](dialect)
        } else {
          input.safeParse[Source](dialect)
        }

        /* If the parse failed, try tokenizing the file to allow tokenization based
         * functionality to work.
         */
        possiblyParsed match {
          case err: Parsed.Error =>
            val tokens = tokenize(path)
            input.safeParseWithExperimentalFallback[Source](
              dialect,
              () => tokens,
            ) match {
              case Parsed.Success(tree) =>
                Parsed.Success(tree)
              case _ =>
                tokens.foreach(tokens => tokenized(path) = tokens)
                err
            }
          case succes: Parsed.Success[_] =>
            tokenized.remove(path)
            succes
        }
      } catch {
        // if the parsers breaks we should not throw the exception further
        case _: StackOverflowError =>
          val newPathCopy = reports
            .unsanitized()
            .create(() =>
              Report(
                s"stackoverflow_${path.filename}",
                text,
                s"Stack overflow in ${path.filename}",
                path = Optional.of(path.toURI),
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

}

object Trees {

  /* Tokenizing works perfectly fine with 212 dialect as long as we are only
   * interested in having stable results. This is not the case for parsing.
   */
  val defaultTokenizerDialect: Dialect = scala.meta.dialects.Scala213

  def defaultTokenized(input: inputs.Input.VirtualFile): Tokenized = {
    input.value.safeTokenize(defaultTokenizerDialect)
  }
}
