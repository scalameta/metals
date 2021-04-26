package scala.meta.internal.mtags

import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CancellationException

import scala.collection.mutable
import scala.{meta => m}

import scala.meta.internal.semanticdb.SymbolInformation.{Property => p}
import scala.meta.io.AbsolutePath
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}

object MtagsEnrichments extends MtagsEnrichments
trait MtagsEnrichments extends CommonMtagsEnrichments {

  implicit class XtensionIteratorCollection[T](it: Iterator[T]) {
    def headOption: Option[T] = {
      if (it.hasNext) Some(it.next())
      else None
    }
    def lastOption: Option[T] = {
      it.foldLeft(Option.empty[T]) { case (_, e) =>
        Some(e)
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

    def stripBackticks: String = value.stripPrefix("`").stripSuffix("`")
    def isBackticked: Boolean =
      value.size > 1 && value.head == '`' && value.last == '`'
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
}
