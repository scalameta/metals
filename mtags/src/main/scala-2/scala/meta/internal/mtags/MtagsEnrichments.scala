package scala.meta.internal.mtags

import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CancellationException

import scala.collection.mutable
import scala.{meta => m}

import scala.meta.XtensionTokenizeInputLike
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.Trees
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.semanticdb.SymbolInformation.{Property => p}
import scala.meta.internal.trees.Origin
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}

object MtagsEnrichments extends MtagsEnrichments
trait MtagsEnrichments extends CommonMtagsEnrichments {
  import Trees.defaultDialect

  implicit class XtensionInputOffset(input: Input) {
    def toLanguage: Language =
      input match {
        case Input.VirtualFile(path, _) =>
          filenameToLanguage(path)
        case _ =>
          Language.UNKNOWN_LANGUAGE
      }

    /**
     * Returns offset position with end == start == offset */
    def toOffsetPosition(offset: Int): Position =
      Position.Range(input, offset, offset)

    /**
     * Returns an offset for this input */
    def toOffset(line: Int, column: Int): Int =
      input.lineToOffset(line) + column

    /**
     * Returns an offset position for this input */
    def toPosition(startLine: Int, startColumn: Int): Position.Range =
      toPosition(startLine, startColumn, startLine, startColumn)

    def toPosition(occ: s.SymbolOccurrence): Position.Range = {
      val range = occ.range.getOrElse(s.Range())
      toPosition(
        range.startLine,
        range.startCharacter,
        range.endLine,
        range.endCharacter
      )
    }

    /**
     * Returns a range position for this input */
    def toPosition(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    ): Position.Range =
      Position.Range(
        input,
        toOffset(startLine, startColumn),
        toOffset(endLine, endColumn)
      )
  }

  implicit class XtensionSymbolInformation(kind: s.SymbolInformation.Kind) {
    def toLSP: l.SymbolKind =
      kind match {
        case k.LOCAL => l.SymbolKind.Variable
        case k.FIELD => l.SymbolKind.Field
        case k.METHOD => l.SymbolKind.Method
        case k.CONSTRUCTOR => l.SymbolKind.Constructor
        case k.MACRO => l.SymbolKind.Method
        case k.TYPE => l.SymbolKind.Class
        case k.PARAMETER => l.SymbolKind.Variable
        case k.SELF_PARAMETER => l.SymbolKind.Variable
        case k.TYPE_PARAMETER => l.SymbolKind.TypeParameter
        case k.OBJECT => l.SymbolKind.Object
        case k.PACKAGE => l.SymbolKind.Module
        case k.PACKAGE_OBJECT => l.SymbolKind.Module
        case k.CLASS => l.SymbolKind.Class
        case k.TRAIT => l.SymbolKind.Interface
        case k.INTERFACE => l.SymbolKind.Interface
        case _ => l.SymbolKind.Class
      }
  }
  implicit class XtensionIteratorCollection[T](it: Iterator[T]) {
    def headOption: Option[T] = {
      if (it.hasNext) Some(it.next())
      else None
    }
    def lastOption: Option[T] = {
      it.foldLeft(Option.empty[T]) {
        case (_, e) => Some(e)
      }
    }
  }

  implicit class XtensionOffsetParams(params: OffsetParams) {
    def isDelimiter: Boolean = {
      params.offset() < 0 ||
      params.offset() >= params.text().length ||
      (params.text().charAt(params.offset()) match {
        case '(' | ')' | '{' | '}' | '[' | ']' | ',' | '=' | '.' => true
        case _ => false
      })
    }
    def isWhitespace: Boolean = {
      params.offset() < 0 ||
      params.offset() >= params.text().length ||
      params.text().charAt(params.offset()).isWhitespace
    }
  }
  implicit class XtensionIterableOps[T](lst: Iterable[T]) {
    def distinctBy[B](fn: T => B): List[T] = {
      new XtensionIteratorOps(lst.iterator).distinctBy(fn)
    }
  }
  implicit class XtensionIteratorOps[T](lst: Iterator[T]) {
    def distinctBy[B](fn: T => B): List[T] = {
      val isVisited = mutable.Set.empty[B]
      val buf = mutable.ListBuffer.empty[T]
      lst.foreach { elem =>
        val hash = fn(elem)
        if (!isVisited(hash)) {
          isVisited += hash
          buf += elem
        }
      }
      buf.result()
    }
  }
  implicit class XtensionCancelChecker(token: CancelChecker) {
    def isCancelled: Boolean =
      try {
        token.checkCanceled()
        false
      } catch {
        case _: CancellationException =>
          true
      }
  }
  implicit class XtensionSemanticdbProperties(properties: Int) {
    def isEnum: Boolean = (properties & p.ENUM.value) != 0
    def isVar: Boolean = (properties & p.VAR.value) != 0
    def isVal: Boolean = (properties & p.VAL.value) != 0
  }

  implicit class XtensionToken(token: m.Token) {
    def isWhiteSpaceOrComment: Boolean =
      token match {
        case _: m.Token.Space | _: m.Token.Tab | _: m.Token.CR | _: m.Token.LF |
            _: m.Token.LFLF | _: m.Token.FF | _: m.Token.Comment |
            _: m.Token.BOF | _: m.Token.EOF =>
          true
        case _ => false
      }
  }

  implicit class XtensionStringMtags(value: String) {

    def toAbsolutePath: AbsolutePath =
      AbsolutePath(Paths.get(URI.create(value.stripPrefix("metals:")))).dealias
    def lastIndexBetween(
        char: Char,
        lowerBound: Int,
        upperBound: Int
    ): Int = {
      val safeLowerBound = Math.max(0, lowerBound)
      var index = upperBound
      while (index >= safeLowerBound && value(index) != char) {
        index -= 1
      }
      if (index < safeLowerBound) -1 else index
    }
  }

  implicit class XtensionPositionLspInverse(pos: l.Position) {
    def toMeta(input: m.Input): m.Position = {
      m.Position.Range(
        input,
        pos.getLine,
        pos.getCharacter,
        pos.getLine,
        pos.getCharacter
      )
    }
  }

  implicit class XtensionTreeTokenStream(tree: m.Tree) {
    def leadingTokens: Iterator[m.Token] =
      tree.origin match {
        case Origin.Parsed(input, _, pos) =>
          val tokens = input.tokenize.get
          tokens.slice(0, pos.start - 1).reverseIterator
        case _ => Iterator.empty
      }

    def trailingTokens: Iterator[m.Token] =
      tree.origin match {
        case Origin.Parsed(input, _, pos) =>
          val tokens = input.tokenize.get
          tokens.slice(pos.end + 1, tokens.length).iterator
        case _ => Iterator.empty
      }

    def findFirstLeading(predicate: m.Token => Boolean): Option[m.Token] =
      leadingTokens.find(predicate)

    def findFirstTrailing(predicate: m.Token => Boolean): Option[m.Token] =
      trailingTokens.find(predicate)
  }
}
