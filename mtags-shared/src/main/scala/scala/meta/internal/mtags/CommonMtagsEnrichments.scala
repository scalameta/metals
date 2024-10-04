package scala.meta.internal.mtags

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.PriorityQueue
import java.util.logging.Level
import java.util.logging.Logger
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.AbstractIterator
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.pc.CompletionItemData
import scala.meta.internal.pc.RangeOffset
import scala.meta.pc.ContentType
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams
import scala.meta.pc.VirtualFileParams

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

  protected def decodeJson[T](
      obj: AnyRef,
      cls: java.lang.Class[T],
      gson: Option[Gson] = None
  ): Option[T] =
    Option(obj).flatMap { data =>
      try {
        Option(
          gson
            .getOrElse(new Gson())
            .fromJson[T](data.asInstanceOf[JsonElement], cls)
        )
      } catch {
        case NonFatal(e) =>
          logger.log(Level.SEVERE, s"decode error: $cls", e)
          None
      }
    }

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

    def getLeftTextEdit(): Option[l.TextEdit] = {
      for {
        either <- Option(item.getTextEdit)
        textEdit <- Option(either.getLeft())
      } yield textEdit
    }
  }

  implicit class XtensionLspPosition(pos: l.Position) {
    def isNone: Boolean =
      pos.getLine() < 0 &&
        pos.getCharacter() < 0

  }

  implicit class XtensionLspRange(range: l.Range) {
    def isOffset: Boolean =
      range.getStart == range.getEnd

    def isNone: Boolean =
      range.getStart().isNone &&
        range.getEnd().isNone

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

    def copy(
        startLine: Int = range.getStart().getLine(),
        startCharacter: Int = range.getStart().getCharacter(),
        endLine: Int = range.getEnd().getLine(),
        endCharacter: Int = range.getEnd().getCharacter()
    ) =
      new l.Range(
        new l.Position(startLine, startCharacter),
        new l.Position(endLine, endCharacter)
      )
  }

  implicit class XtensionNIOPath(path: Path) {
    def filename: String = path.getFileName().toString()
    def exists: Boolean = {
      Files.exists(path)
    }

    def toURI: URI = {
      toURI(Files.isDirectory(path))
    }

    def toURI(isDirectory: Boolean): URI = {
      val suffix = if (isDirectory) "/" else ""
      // Can't use toNIO.toUri because it produces an absolute URI.
      val names = path.iterator().asScala
      val uris = names.map { name =>
        // URI encode each part of the path individually.
        new URI(null, null, name.toString, null)
      }
      URI.create(uris.mkString("", "/", suffix))
    }

    // Using [[Files.isSymbolicLink]] is not enough.
    // It will be false when one of the parents is a symlink (e.g. /dir/link/file.txt)
    def dealias: Path = {
      if (exists) { // cannot dealias non-existing path
        path.toRealPath()
      } else {
        path
      }
    }

    def createDirectories(): Path =
      Files.createDirectories(path.dealias)

    def writeText(text: String): Unit = {
      path.getParent.createDirectories()
      val tmp = Files.createTempFile("metals", path.filename)
      // Write contents first to a temporary file and then try to
      // atomically move the file to the destination. The atomic move
      // reduces the risk that another tool will concurrently read the
      // file contents during a half-complete file write.
      Files.write(
        tmp,
        text.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.TRUNCATE_EXISTING
      )
      try {
        Files.move(
          tmp,
          path,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      } catch {
        case NonFatal(_) =>
          Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
      }
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
    def isScalaCLIGeneratedFile: Boolean =
      doc.endsWith(".sc.scala") && !isAmmoniteGeneratedFile
    def isAmmoniteScript: Boolean =
      isScalaScript && !isWorksheet && !doc.endsWith("/build.sc")
    def isMill: Boolean =
      doc.endsWith(".mill") ||
        doc.endsWith(".scala.mill") ||
        doc.endsWith("/build.sc")
    def endsWithAt(value: String, offset: Int): Boolean = {
      val start = offset - value.length
      start >= 0 &&
      doc.startsWith(value, start)
    }
    def toMarkupContent(
        contentType: ContentType = ContentType.MARKDOWN
    ): l.MarkupContent = {
      val content = new MarkupContent
      content.setKind(contentType.toString())
      content.setValue(doc)
      content
    }
  }

  def extendRangeToIncludeWhiteCharsAndTheFollowingNewLine(
      source: Array[Char],
      acceptedAdditionalTrailingChars: List[Char] = List()
  )(
      startOffset: Int,
      endOffset: Int
  ): (Int, Int) = {
    @tailrec
    def expandRec(
        step: Int,
        currentIndex: Int,
        acceptedChars: List[Char] = List('\t', ' ')
    ): Int = {
      val nextIndex = currentIndex + step
      if (
        nextIndex >= 0
        && nextIndex < source.size
        && acceptedChars.contains(source(nextIndex))
      ) expandRec(step, nextIndex)
      else currentIndex
    }

    val startWithSpace = expandRec(-1, startOffset)
    val endWithSpace =
      if (startWithSpace == 0 || source(startWithSpace - 1) == '\n')
        expandRec(
          1,
          endOffset - 1,
          List('\t', ' ', ';') ++ acceptedAdditionalTrailingChars
        ) + 1
      else
        expandRec(
          1,
          endOffset - 1,
          List('\t', ' ') ++ acceptedAdditionalTrailingChars
        ) + 1
    val endCharsAcceptedOnce = List(';', '\n')
    if (
      endWithSpace < source.size
      && endCharsAcceptedOnce.contains(source(endWithSpace))
    )
      (startWithSpace, endWithSpace + 1)
    else (startWithSpace, endWithSpace)
  }

  implicit class XtensionJavaPriorityQueue[A](q: PriorityQueue[A]) {

    /**
     * Returns iterator that consumes the priority queue in-order using `poll()`.
     */
    def pollingIterator: Iterator[A] =
      new AbstractIterator[A] {
        override def hasNext: Boolean = !q.isEmpty
        override def next(): A = q.poll()
      }

  }

  implicit class XtensionVirtualFileParams(params: VirtualFileParams) {
    def printed(marker: String = "@@"): String = {
      def textWithPosMarker(markerPos: Int, text: String) =
        if (markerPos < 0) marker ++ text
        else if (markerPos >= text.length()) text ++ marker
        else {
          val (head, tail) = text.splitAt(markerPos)
          head ++ marker ++ tail
        }
      params match {
        case r: RangeOffset =>
          val withStartMarker = textWithPosMarker(r.start, r.text())
          val withMarkers =
            textWithPosMarker(r.end + marker.length(), withStartMarker)
          s"""|range: ${r.start} - ${r.end}
              |uri: ${r.uri()}
              |text:
              |```scala
              |$withMarkers
              |```
              |""".stripMargin
        case o: OffsetParams =>
          s"""|offset: ${o.offset()}
              |uri: ${o.uri()}
              |text:
              |```scala
              |${textWithPosMarker(o.offset(), o.text())}
              |```
              |""".stripMargin
        case v =>
          s"""|uri: ${v.uri()}
              |text:
              |```scala
              |${v.text()}
              |```
              |""".stripMargin
      }
    }
  }

  implicit class XtensionText(text: String) {
    def trimTo(maxLength: Int): String =
      if (text.length() <= maxLength) text
      else s"${text.take(maxLength)}..."

    def allIndexesOf(
        str: String
    ): List[Int] = {
      val buffer = ListBuffer.empty[Int]
      var index = text.indexOf(str)
      while (index >= 0) {
        buffer += index
        index = text.indexOf(str, index + 1)
      }
      buffer.toList
    }

    def findIndicesOf(symbols: List[Char]): List[Int] = {
      @tailrec
      def loop(
          index: Int,
          afterEscape: Boolean,
          inBackticks: Boolean,
          acc: List[Int]
      ): List[Int] =
        if (index >= text.length()) acc.reverse
        else {
          val c = text.charAt(index)
          val newAcc =
            if (symbols.contains(c) && !inBackticks && !afterEscape)
              index :: acc
            else acc
          loop(
            index + 1,
            afterEscape = c == '\\',
            inBackticks = c == '`' ^ inBackticks,
            acc = newAcc
          )
        }
      loop(
        index = 0,
        afterEscape = false,
        inBackticks = false,
        acc = List.empty
      )
    }
  }

  implicit class CommonXtensionList[T](lst: List[T]) {
    def get(i: Int): Option[T] =
      if (i >= 0 && i < lst.length) Some(lst(i))
      else None
  }

}
