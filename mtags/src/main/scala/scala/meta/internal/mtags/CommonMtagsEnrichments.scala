package scala.meta.internal.mtags

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.logging.Level
import java.util.logging.Logger
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.AbstractIterator
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal
import scala.{meta => m}

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.pc.CompletionItemData
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import com.google.gson.Gson
import com.google.gson.JsonElement
import geny.Generator
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.{lsp4j => l}

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

  implicit class XtensionMetaPosition(pos: m.Position) {
    def toSemanticdb: s.Range = {
      new s.Range(
        pos.startLine,
        pos.startColumn,
        pos.endLine,
        pos.endColumn
      )
    }
    def toLsp: l.Range = {
      new l.Range(
        new l.Position(pos.startLine, pos.startColumn),
        new l.Position(pos.endLine, pos.endColumn)
      )
    }
    def encloses(other: m.Position): Boolean = {
      pos.start <= other.start && pos.end >= other.end
    }

    def encloses(other: l.Range): Boolean = {
      val start = other.getStart()
      val end = other.getEnd()
      val isBefore =
        pos.startLine < start.getLine ||
          (pos.startLine == start.getLine && pos.startColumn <= start
            .getCharacter())

      val isAfter = pos.endLine > end.getLine() ||
        (pos.endLine >= end.getLine() && pos.endColumn >= end.getCharacter())

      isBefore && isAfter
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

  implicit class XtensionSemanticdbRange(range: s.Range) {
    def isPoint: Boolean = {
      range.startLine == range.endLine &&
      range.startCharacter == range.endCharacter
    }
    def isEqual(other: s.Range): Boolean = {
      range.startLine == other.startLine &&
      range.startCharacter == other.startCharacter &&
      range.endLine == other.endLine &&
      range.endCharacter == other.endCharacter
    }
    def encloses(other: s.Range): Boolean = {
      val startsBeforeOrAt =
        range.startLine < other.startLine ||
          (range.startLine == other.startLine &&
            range.startCharacter <= other.startCharacter)
      val endsAtOrAfter =
        range.endLine > other.endLine ||
          (range.endLine == other.endLine &&
            range.endCharacter >= other.endCharacter)
      startsBeforeOrAt && endsAtOrAfter
    }
    def toLocation(uri: String): l.Location = {
      new l.Location(uri, range.toLsp)
    }
    def toLsp: l.Range = {
      val start = new l.Position(range.startLine, range.startCharacter)
      val end = new l.Position(range.endLine, range.endCharacter)
      new l.Range(start, end)
    }
    def encloses(
        other: l.Position,
        includeLastCharacter: Boolean = false
    ): Boolean = {
      val startsBeforeOrAt =
        range.startLine < other.getLine ||
          (range.startLine == other.getLine &&
            range.startCharacter <= other.getCharacter)
      val endCharCondition = {
        if (includeLastCharacter)
          range.endCharacter >= other.getCharacter
        else
          range.endCharacter > other.getCharacter
      }
      val endsAtOrAfter =
        range.endLine > other.getLine ||
          (range.endLine == other.getLine &&
            endCharCondition)
      startsBeforeOrAt && endsAtOrAfter
    }
    def encloses(other: l.Range): Boolean = {
      encloses(other.getStart) &&
      encloses(other.getEnd)
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

    def toMeta(input: m.Input): Option[m.Position] =
      if (range.isNone) {
        None
      } else {
        Try(
          m.Position.Range(
            input,
            range.getStart.getLine,
            range.getStart.getCharacter,
            range.getEnd.getLine,
            range.getEnd.getCharacter
          )
        ).toOption
      }

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

  protected def filenameToLanguage(filename: String): Language = {
    if (filename.endsWith(".java")) Language.JAVA
    else if (
      filename.endsWith(".scala") || filename.endsWith(".sc")
      || filename.endsWith(".sbt")
    )
      Language.SCALA
    else Language.UNKNOWN_LANGUAGE
  }

  implicit class XtensionPathMetals(file: Path) {
    def isClassfile: Boolean = filename.endsWith(".class")
    def filename: String = file.getFileName().toString()
    def toLanguage: Language = {
      val filename = file.getFileName
      if (filename == null) Language.UNKNOWN_LANGUAGE
      else filenameToLanguage(filename.toString)
    }
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

  implicit class XtensionInputVirtual(input: Input.VirtualFile) {
    def filename: String = {
      Try {
        val uri = URI.create(input.path)
        Paths.get(uri).filename
      } match {
        case Failure(exception) =>
          logger.warning(exception.getMessage())
          Try {
            Paths.get(input.path).filename
          } match {
            case Failure(exception) =>
              logger.warning(exception.getMessage())
              input.path.reverse.takeWhile(c => c != '/' && c != '\\').reverse
            case Success(value) =>
              value
          }
        case Success(value) =>
          value
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

    def checkIfNotInComment(
        treeStart: Int,
        treeEnd: Int,
        currentOffset: Int
    ): Boolean = {
      import scala.meta._
      val text = doc.slice(treeStart, treeEnd)
      val tokens = text.tokenize.toOption
      tokens
        .flatMap(t =>
          t.find {
            case t: Token.Comment
                if treeStart + t.pos.start < currentOffset &&
                  treeStart + t.pos.end >= currentOffset =>
              true
            case _ =>
              false
          }
        )
        .isEmpty
    }
  }

  implicit class XtensionRelativePathMetals(file: RelativePath) {
    def filename: String = file.toNIO.filename
    def isScalaFilename: Boolean = filename.isScalaFilename
    def isJavaFilename: Boolean = filename.isJavaFilename
    def isScalaOrJavaFilename: Boolean = isScalaFilename || isJavaFilename
  }

  implicit class XtensionStream[A](stream: java.util.stream.Stream[A]) {
    import scala.collection.JavaConverters._
    def asScala: Generator[A] = {
      Generator.selfClosing((stream.iterator.asScala, () => stream.close()))
    }
  }

  implicit class XtensionAbsolutePath(path: AbsolutePath) {
    def isEmptyDirectory: Boolean = {
      path.isDirectory &&
      !path.list.exists(_ => true)
    }
    def parent: AbsolutePath = {
      AbsolutePath(path.toNIO.getParent)
    }

    def parentOpt: Option[AbsolutePath] = {
      if (hasParent)
        Some(AbsolutePath(path.toNIO.getParent))
      else
        None
    }

    def hasParent: Boolean = {
      path.toNIO.getParent != null
    }

    def exists: Boolean = {
      Files.exists(path.toNIO)
    }

    def root: Option[AbsolutePath] =
      Option(path.toNIO.getRoot()).map(AbsolutePath(_))

    def list: Generator[AbsolutePath] = {
      if (path.isDirectory) Files.list(path.toNIO).asScala.map(AbsolutePath(_))
      else Generator()
    }

    def listRecursive: Generator[AbsolutePath] = {
      if (path.isDirectory) Files.walk(path.toNIO).asScala.map(AbsolutePath(_))
      else if (path.isFile) Generator(path)
      else Generator()
    }

    // Using [[Files.isSymbolicLink]] is not enough.
    // It will be false when one of the parents is a symlink (e.g. /dir/link/file.txt)
    def dealias: AbsolutePath = {
      if (exists) { // cannot dealias non-existing path
        AbsolutePath(path.toNIO.toRealPath())
      } else {
        path
      }
    }

    def readText: String = {
      FileIO.slurp(path, StandardCharsets.UTF_8)
    }

    def readTextOpt: Option[String] = {
      if (path.exists) {
        Option(path.readText)
      } else {
        None
      }
    }

    def filename: String = path.toNIO.filename

    def toIdeallyRelativeURI(sourceItemOpt: Option[AbsolutePath]): String =
      sourceItemOpt match {
        case Some(sourceItem) =>
          if (sourceItem.isScalaOrJava) {
            sourceItem.toNIO.getFileName().toString()
          } else {
            path.toRelative(sourceItem).toURI(false).toString
          }
        case None =>
          path.toURI.toString
      }
    def isBuild: Boolean =
      path.filename.startsWith("BUILD")

    def isInBspDirectory(workspace: AbsolutePath): Boolean =
      path.toNIO.startsWith(workspace.resolve(".bsp").toNIO)

    def isScalaOrJava: Boolean = {
      toLanguage match {
        case Language.SCALA | Language.JAVA => true
        case _ => false
      }
    }
    def isSbt: Boolean = {
      filename.endsWith(".sbt")
    }
    def isClassfile: Boolean = {
      filename.endsWith(".class")
    }
    def isTasty: Boolean = {
      filename.endsWith(".tasty")
    }
    def isScalaScript: Boolean = {
      filename.endsWith(".sc")
    }
    def isMill: Boolean = isScalaScript && filename == "build.sc"
    def isAmmoniteScript: Boolean =
      isScalaScript && !isWorksheet && !isMill
    def isWorksheet: Boolean = {
      filename.endsWith(".worksheet.sc")
    }
    def isJavaFilename: Boolean = {
      filename.isJavaFilename
    }
    def isScalaFilename: Boolean = {
      filename.isScalaFilename
    }
    def isScala: Boolean = {
      toLanguage == Language.SCALA
    }
    def isJava: Boolean = {
      toLanguage == Language.JAVA
    }
    def isSemanticdb: Boolean = {
      path.toNIO.getFileName.toString.endsWith(".semanticdb")
    }
    def extension: String = PathIO.extension(path.toNIO)
    def toLanguage: Language = {
      path.toNIO.toLanguage
    }
    def toInput: Input.VirtualFile = {
      val text = FileIO.slurp(path, StandardCharsets.UTF_8)
      val file = path.toURI.toString()
      Input.VirtualFile(file, text)
    }

    def jarPath: Option[AbsolutePath] = {
      val filesystem = path.toNIO.getFileSystem()
      if (filesystem.provider().getScheme().equals("jar")) {
        Some(
          AbsolutePath(
            Paths.get(filesystem.toString)
          )
        )
      } else {
        None
      }
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

  implicit class XtensionSymbolInformation(kind: s.SymbolInformation.Kind) {
    def toLsp: l.SymbolKind =
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

  implicit class XtensionInputOffset(input: Input) {
    def toLanguage: Language =
      input match {
        case Input.VirtualFile(path, _) =>
          filenameToLanguage(path)
        case _ =>
          Language.UNKNOWN_LANGUAGE
      }

    /**
     * Returns offset position with end == start == offset
     */
    def toOffsetPosition(offset: Int): Position =
      Position.Range(input, offset, offset)

    /**
     * Returns an offset for this input
     */
    def toOffset(line: Int, column: Int): Int =
      input.lineToOffset(line) + column

    /**
     * Returns an offset position for this input
     */
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
     * Returns a range position for this input
     */
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
}
