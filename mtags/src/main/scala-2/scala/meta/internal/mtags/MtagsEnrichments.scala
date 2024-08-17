package scala.meta.internal.mtags

import scala.collection.mutable
import scala.reflect.internal.util.Position

import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams
import scala.meta.tokens.Token

object MtagsEnrichments extends MtagsEnrichments
trait MtagsEnrichments extends ScalametaCommonEnrichments {

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

    def prevIsWhitespaceOrDelimeter: Boolean = {
      val prevOffset = params.offset() - 1
      prevOffset < 0 ||
      prevOffset >= params.text().length ||
      (params.text().charAt(prevOffset) match {
        case '(' | ')' | '{' | '}' | '[' | ']' | ',' | '=' | '.' => true
        case w if w.isWhitespace => true
        case _ => false
      })
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

  implicit class XtensionPositionMtags(pos: Position) {
    def encloses(other: Position): Boolean =
      pos.start <= other.start && pos.end >= other.end

    def encloses(other: RangeParams): Boolean =
      pos.start <= other.offset() && pos.end >= other.endOffset()

    def adjust(
        text: Array[Char],
        forRename: Boolean = false
    ): (Position, Boolean) = {
      val isBackticked = text(pos.start) == '`' &&
        text(pos.end - 1) == '`' &&
        pos.start != (pos.end - 1) // for one character names, e.g. `c`
      //                                                    start-^^-end
      lazy val backtickedSetter =
        text(pos.start) == '`' && text.length > pos.end + 4 && text
          .slice(pos.end + 1, pos.end + 4)
          .mkString == backtickedSetterEnding
      val isOldNameBackticked = text(pos.start) == '`' &&
        (text(pos.end - 1) != '`' || pos.start == (pos.end - 1)) &&
        text(pos.end + 1) == '`'
      if (isBackticked && forRename)
        (pos.withStart(pos.start + 1).withEnd(pos.end - 1), true)
      else if (backtickedSetter)
        (pos.withStart(pos.start + 1).withEnd(pos.end + 1), false)
      else if (isOldNameBackticked) // pos
        (pos.withEnd(pos.end + 2), false)
      else (pos, false)
    }
  }

  private val backtickedSetterEnding = "_=`"

  implicit class XtensionRangeParameters(pos: RangeParams) {
    def encloses(other: Position): Boolean =
      pos.offset <= other.start && pos.endOffset >= other.end
  }

}
