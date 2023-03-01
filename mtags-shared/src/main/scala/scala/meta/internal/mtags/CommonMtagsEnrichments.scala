package scala.meta.internal.mtags

import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.logging.Level
import java.util.logging.Logger
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.AbstractIterator
import scala.util.control.NonFatal

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.pc.CompletionItemData
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.{lsp4j => l}

object CommonMtagsEnrichments extends CommonMtagsEnrichments {}
trait CommonMtagsEnrichments {

  private def logger: Logger =
    Logger.getLogger(classOf[CommonMtagsEnrichments].getName)

  protected def decodeJson[T](obj: AnyRef, cls: java.lang.Class[T]): Option[T] =
    for {
      data <- Option(obj)
      value <-
        try {
          Some(
            new Gson().fromJson[T](
              data.asInstanceOf[JsonElement],
              cls
            )
          )
        } catch {
          case NonFatal(e) =>
            logger.log(Level.SEVERE, s"decode error: $cls", e)
            None
        }
    } yield value

  implicit class XtensionJEitherCross[A, B](either: JEither[A, B]) {
    def asScala: Either[A, B] =
      if (either.isLeft) Left(either.getLeft)
      else Right(either.getRight)
  }

  implicit class XtensionEitherCross[A, B](either: Either[A, B]) {
    def asJava: JEither[A, B] =
      either match {
        case Left(value) => JEither.forLeft(value)
        case Right(value) => JEither.forRight(value)
      }

    def mapLeft[C](f: A => C): Either[C, B] = either match {
      case Left(value) => Left(f(value))
      case Right(value) => Right(value)
    }
  }

  implicit class XtensionRangeParams(params: RangeParams) {

    def trimWhitespaceInRange: Option[OffsetParams] = {
      def isWhitespace(i: Int): Boolean =
        params.text.charAt(i).isWhitespace

      @tailrec
      def trim(start: Int, end: Int): Option[(Int, Int)] =
        if (start == end) Some((start, start)).filter(_ => !isWhitespace(start))
        else if (isWhitespace(start)) trim(start + 1, end)
        else if (isWhitespace(end - 1)) trim(start, end - 1)
        else Some((start, end))

      trim(params.offset, params.endOffset()).map { case (start, end) =>
        if (start == end)
          CompilerOffsetParams(params.uri, params.text, start, params.token)
        else
          CompilerRangeParams(params.uri, params.text, start, end, params.token)
      }
    }
  }

  implicit class XtensionOptionalJava[T](opt: ju.Optional[T]) {
    def asScala: Option[T] =
      if (opt.isPresent) Some(opt.get())
      else None
  }

  implicit class XtensionOptionScala[T](opt: Option[T]) {
    def asJava: ju.Optional[T] =
      if (opt.isDefined) ju.Optional.of(opt.get)
      else ju.Optional.empty()
  }

  implicit class XtensionCompletionItemData(item: CompletionItem) {
    def data: Option[CompletionItemData] =
      item.getData match {
        case d: CompletionItemData =>
          Some(d)
        case data =>
          decodeJson(data, classOf[CompletionItemData])
      }

    def setTextEdit(edit: l.TextEdit): Unit = {
      item.setTextEdit(JEither.forLeft(edit))
    }

  }

  implicit class XtensionLspRange(range: l.Range) {
    def isOffset: Boolean =
      range.getStart == range.getEnd

    def encloses(position: l.Position): Boolean = {
      val startsBeforeOrAt =
        range.getStart.getLine < position.getLine ||
          (range.getStart.getLine == position.getLine &&
            range.getStart.getCharacter <= position.getCharacter)
      val endsAtOrAfter =
        range.getEnd.getLine > position.getLine ||
          (range.getEnd.getLine == position.getLine &&
            range.getEnd.getCharacter >= position.getCharacter)
      startsBeforeOrAt && endsAtOrAfter
    }

    def encloses(other: l.Range): Boolean =
      encloses(other.getStart) && encloses(other.getEnd)

    def overlapsWith(other: l.Range): Boolean = {
      val startsBeforeOtherEnds =
        range.getStart.getLine < other.getEnd.getLine ||
          (range.getStart.getLine == other.getEnd.getLine &&
            range.getStart.getCharacter <= other.getEnd.getCharacter)

      val endsAfterOtherStarts =
        range.getEnd.getLine > other.getStart.getLine ||
          (range.getEnd.getLine == other.getStart.getLine &&
            range.getEnd.getCharacter >= other.getStart.getCharacter)

      startsBeforeOtherEnds && endsAfterOtherStarts
    }

  }

  implicit class XtensionPathMetals(file: Path) {
    def semanticdbRoot: Option[Path] = {
      val end = Paths.get("META-INF").resolve("semanticdb")
      @tailrec def root(path: Path): Option[Path] = {
        if (path.endsWith(end)) Some(path)
        else {
          Option(path.getParent) match {
            case Some(parent) => root(parent)
            case _ => None
          }
        }
      }
      root(file)
    }
  }

  implicit class XtensionStringDoc(doc: String) {
    def isScala: Boolean =
      doc.endsWith(".scala")
    def isSbt: Boolean =
      doc.endsWith(".sbt")
    def isScalaScript: Boolean =
      doc.endsWith(".sc")
    def isWorksheet: Boolean =
      doc.endsWith(".worksheet.sc")
    def isScalaFilename: Boolean =
      doc.isScala || isScalaScript || isSbt
    def isScalaOrJavaFilename: Boolean =
      doc.isScala || isScalaScript || isSbt || isJavaFilename
    def isJavaFilename: Boolean =
      doc.endsWith(".java")
    def isAmmoniteGeneratedFile: Boolean =
      doc.endsWith(".amm.sc.scala")
    def isAmmoniteScript: Boolean =
      isScalaScript && !isWorksheet && !doc.endsWith("/build.sc")
    def isMill: Boolean =
      doc.endsWith("/build.sc")
    def asSymbol: Symbol = Symbol(doc)
    def endsWithAt(value: String, offset: Int): Boolean = {
      val start = offset - value.length
      start >= 0 &&
      doc.startsWith(value, start)
    }
    def toMarkupContent: l.MarkupContent = {
      val content = new MarkupContent
      content.setKind("markdown")
      content.setValue(doc)
      content
    }
  }

  implicit class XtensionJavaPriorityQueue[A](q: util.PriorityQueue[A]) {

    /**
     * Returns iterator that consumes the priority queue in-order using `poll()`.
     */
    def pollingIterator: Iterator[A] =
      new AbstractIterator[A] {
        override def hasNext: Boolean = !q.isEmpty
        override def next(): A = q.poll()
      }

  }

}
