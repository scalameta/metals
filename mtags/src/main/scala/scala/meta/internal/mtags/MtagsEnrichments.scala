package scala.meta.internal.mtags

import com.google.gson.Gson
import com.google.gson.JsonElement
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.logging.Level
import java.util.logging.Logger
import geny.Generator
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import scala.annotation.tailrec
import scala.collection.AbstractIterator
import scala.collection.mutable
import scala.meta.pc.OffsetParams
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.pc.CompletionItemData
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.semanticdb.SymbolInformation.{Property => p}
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal
import scala.{meta => m}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import scala.meta.io.RelativePath

object MtagsEnrichments extends MtagsEnrichments
trait MtagsEnrichments {
  implicit class XtensionRange(range: s.Range) {
    def isPoint: Boolean = {
      range.startLine == range.endLine &&
      range.startCharacter == range.endCharacter
    }
    def encloses(other: s.Range): Boolean = {
      range.startLine <= other.startLine &&
      range.endLine >= other.endLine &&
      range.startCharacter <= other.startCharacter && {
        range.endCharacter > other.endCharacter ||
        other == range
      }
    }
  }
  private def filenameToLanguage(filename: String): Language = {
    if (filename.endsWith(".java")) Language.JAVA
    else if (filename.endsWith(".scala")) Language.SCALA
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

  implicit class XtensionRelativePathMetals(file: RelativePath) {
    def filename: String = file.toNIO.filename
    def isScalaFilename: Boolean = filename.isScalaFilename
  }

  implicit class XtensionAbsolutePathMetals(file: AbsolutePath) {

    def filename: String = file.toNIO.filename

    def toIdeallyRelativeURI(sourceItemOpt: Option[AbsolutePath]): String =
      sourceItemOpt match {
        case Some(sourceItem) =>
          if (sourceItem.isScalaOrJava) {
            sourceItem.toNIO.getFileName().toString()
          } else {
            file.toRelative(sourceItem).toURI(false).toString
          }
        case None =>
          file.toURI.toString
      }
    def isScalaOrJava: Boolean = {
      toLanguage match {
        case Language.SCALA | Language.JAVA => true
        case _ => false
      }
    }
    def isWorksheet: Boolean = {
      filename.endsWith(".worksheet.sc")
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
      file.toNIO.getFileName.toString.endsWith(".semanticdb")
    }
    def extension: String = PathIO.extension(file.toNIO)
    def toLanguage: Language = {
      file.toNIO.toLanguage
    }
    def toInput: Input.VirtualFile = {
      val text = FileIO.slurp(file, StandardCharsets.UTF_8)
      val path = file.toString()
      val input = Input.VirtualFile(path, text)
      input
    }
  }

  implicit class XtensionInputOffset(input: Input) {
    def toLanguage: Language = input match {
      case Input.VirtualFile(path, _) =>
        filenameToLanguage(path)
      case _ =>
        Language.UNKNOWN_LANGUAGE
    }

    /** Returns offset position with end == start == offset */
    def toOffsetPosition(offset: Int): Position =
      Position.Range(input, offset, offset)

    /** Returns an offset for this input */
    def toOffset(line: Int, column: Int): Int =
      input.lineToOffset(line) + column

    /** Returns an offset position for this input */
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

    /** Returns a range position for this input */
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

  implicit class XtensionJavaPriorityQueue[A](q: util.PriorityQueue[A]) {

    /**
     * Returns iterator that consumes the priority queue in-order using `poll()`.
     */
    def pollingIterator: Iterator[A] = new AbstractIterator[A] {
      override def hasNext: Boolean = !q.isEmpty
      override def next(): A = q.poll()
    }

  }

  private def logger: Logger =
    Logger.getLogger(classOf[MtagsEnrichments].getName)

  protected def decodeJson[T](obj: AnyRef, cls: java.lang.Class[T]): Option[T] =
    for {
      data <- Option(obj)
      value <- try {
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

  implicit class XtensionCompletionItemData(item: CompletionItem) {
    def data: Option[CompletionItemData] =
      item.getData match {
        case d: CompletionItemData =>
          Some(d)
        case data =>
          decodeJson(data, classOf[CompletionItemData])
      }
  }
  implicit class XtensionStringDoc(doc: String) {
    def isScalaScript: Boolean =
      doc.endsWith(".sc")
    def isScalaFilename: Boolean =
      doc.endsWith(".scala") ||
        doc.endsWith(".sc")
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
  implicit class XtensionRangeBuildProtocol(range: s.Range) {
    def toLocation(uri: String): l.Location = {
      new l.Location(uri, range.toLSP)
    }
    def toLSP: l.Range = {
      val start = new l.Position(range.startLine, range.startCharacter)
      val end = new l.Position(range.endLine, range.endCharacter)
      new l.Range(start, end)
    }
    def encloses(
        other: l.Position,
        includeLastCharacter: Boolean = false
    ): Boolean = {
      range.startLine <= other.getLine &&
      range.endLine >= other.getLine &&
      range.startCharacter <= other.getCharacter && {
        if (includeLastCharacter) range.endCharacter >= other.getCharacter
        else range.endCharacter > other.getCharacter
      }
    }
    def encloses(other: l.Range): Boolean = {
      encloses(other.getStart) &&
      encloses(other.getEnd)
    }
  }

  implicit class XtensionSymbolInformation(kind: s.SymbolInformation.Kind) {
    def toLSP: l.SymbolKind = kind match {
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

  implicit class XtensionLspRange(range: l.Range) {
    def isOffset: Boolean =
      range.getStart == range.getEnd
    def toMeta(input: m.Input): m.Position =
      m.Position.Range(
        input,
        range.getStart.getLine,
        range.getStart.getCharacter,
        range.getEnd.getLine,
        range.getEnd.getCharacter
      )
  }

  implicit class XtensionOptionalJava[T](opt: Optional[T]) {
    def asScala: Option[T] =
      if (opt.isPresent) Some(opt.get())
      else None
  }

  implicit class XtensionJEitherCross[A, B](either: JEither[A, B]) {
    def asScala: Either[A, B] =
      if (either.isLeft) Left(either.getLeft)
      else Right(either.getRight)
  }

  implicit class XtensionPositionLsp(pos: m.Position) {
    def toSemanticdb: s.Range = {
      new s.Range(
        pos.startLine,
        pos.startColumn,
        pos.endLine,
        pos.endColumn
      )
    }
    def toLSP: l.Range = {
      new l.Range(
        new l.Position(pos.startLine, pos.startColumn),
        new l.Position(pos.endLine, pos.endColumn)
      )
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

  implicit class XtensionStream[A](stream: java.util.stream.Stream[A]) {
    import scala.collection.JavaConverters._
    def asScala: Generator[A] = {
      Generator.selfClosing((stream.iterator.asScala, () => stream.close()))
    }
  }

  implicit class XtensionAbsolutePath(path: AbsolutePath) {
    def parent: AbsolutePath = {
      AbsolutePath(path.toNIO.getParent)
    }

    def exists: Boolean = {
      Files.exists(path.toNIO)
    }

    def list: Generator[AbsolutePath] = {
      if (path.isDirectory) Files.list(path.toNIO).asScala.map(AbsolutePath(_))
      else Generator()
    }

    def listRecursive: Generator[AbsolutePath] = {
      if (path.isDirectory) Files.walk(path.toNIO).asScala.map(AbsolutePath(_))
      else if (path.isFile) Generator(path)
      else Generator()
    }
  }
}
