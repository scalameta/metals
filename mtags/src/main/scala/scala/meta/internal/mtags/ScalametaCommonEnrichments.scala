package scala.meta.internal.mtags

import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.logging.Level
import java.util.logging.Logger

import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal
import scala.{meta => m}

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

import geny.Generator
import org.eclipse.{lsp4j => l}
import org.scalameta.UnreachableError
import org.scalameta.invariants.InvariantFailedException

object ScalametaCommonEnrichments extends ScalametaCommonEnrichments {}
trait ScalametaCommonEnrichments extends CommonMtagsEnrichments {

  def indexAfterSpacesAndComments(text: Array[Char]): Int = {
    var isInComment = false
    var startedStateChange = false
    val index = text.indexWhere {
      case '/' if !isInComment && !startedStateChange =>
        startedStateChange = true
        false
      case '*' if !isInComment && startedStateChange =>
        startedStateChange = false
        isInComment = true
        false
      case '/' if isInComment && startedStateChange =>
        startedStateChange = false
        isInComment = false
        false
      case '*' if isInComment && !startedStateChange =>
        startedStateChange = true
        false
      case c if isInComment || c.isSpaceChar || c == '\t' =>
        startedStateChange = false
        false
      case _ => true
    }
    if (startedStateChange) index - 1
    else index
  }

  private def logger: Logger =
    Logger.getLogger(classOf[ScalametaCommonEnrichments].getName)

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

  implicit class XtensionLspRangeMeta(range: l.Range) {
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
  }

  implicit class XtensionPositionLspInverse(pos: l.Position) {

    /**
     * LSP position translated to scalameta position. Might return None if
     * pos is not contained in input
     *
     * @param input file input the position relates to
     * @return scalameta position with offset if the pos is contained in the file
     */
    def toMeta(input: m.Input): Option[m.Position] = {
      Try(
        m.Position.Range(
          input,
          pos.getLine,
          pos.getCharacter,
          pos.getLine,
          pos.getCharacter
        )
      ).toOption
    }
  }

  protected def filenameToLanguage(filename: String): Language = {
    if (filename.endsWith(".java")) Language.JAVA
    else if (
      filename.endsWith(".scala") || filename.endsWith(".sc")
      || filename.endsWith(".sbt") || filename.endsWith(".mill")
    )
      Language.SCALA
    else Language.UNKNOWN_LANGUAGE
  }

  implicit class XtensionPathMetals(file: Path) {
    def isClassfile: Boolean = file.filename.endsWith(".class")
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

  implicit class XtensionStringDocMeta(doc: String) {

    import scala.meta._

    def safeTokenize(implicit
        dialect: m.Dialect
    ): Tokenized = try {
      doc.tokenize
    } catch {
      case invariant: InvariantFailedException =>
        logger.log(
          Level.SEVERE,
          s"Got invariant failed exception for '${doc}', which should not happen:\n" +
            invariant.getMessage()
        )
        Tokenized.Error(m.Position.None, invariant.getMessage(), invariant)
      case unreachable: UnreachableError =>
        logger.log(
          Level.SEVERE,
          s"Got unreachable exception for '${doc}', which should not happen:\n" +
            unreachable.getMessage()
        )
        Tokenized.Error(
          m.Position.None,
          unreachable.getMessage(),
          new RuntimeException(unreachable.getMessage())
        )
    }

    def asSymbol: Symbol = Symbol(doc)

    def checkIfNotInComment(
        treeStart: Int,
        treeEnd: Int,
        currentOffset: Int
    ): Boolean = {
      val text = doc.slice(treeStart, treeEnd)
      val tokens = text.safeTokenize.toOption
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
    import scala.meta.internal.jdk.CollectionConverters._
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

    def isRoot: Boolean = path.toNIO.getRoot() == path.toNIO

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

    def readText(charset: Charset): String = {
      FileIO.slurp(path, charset)
    }

    def readText: String = {
      readText(StandardCharsets.UTF_8)
    }

    def readTextOpt: Option[String] = {
      if (path.exists) {
        Option(path.readText)
      } else {
        None
      }
    }

    def filename: String = path.toNIO.filename

    def scalaFileName: String =
      path.filename.stripSuffix(".scala").stripSuffix(".sc")

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

    /*
     * This checks if the file has an extension that indicates it's a
     * Java or Scala file.
     *
     * This also needs to check if the file exists and is an actual file,
     * since this might actually be a directory ending with the extension.
     *
     * If the path does not yet exist we assume it's a file if an extension
     * is present.
     */
    def isScalaOrJava: Boolean = {
      toLanguage match {
        case Language.SCALA | Language.JAVA => isFile
        case _ => false
      }
    }

    def isFile: Boolean =
      !Files.exists(path.toNIO) ||
        Files.isRegularFile(path.toNIO)

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
      filename.endsWith(".sc") && !isWorksheet && !isMill
    }
    def isSourcesJar: Boolean = {
      filename.endsWith("-sources.jar") || filename == "src.zip"
    }
    def isMillBuild: Boolean =
      filename.endsWith(".mill") || filename.endsWith(".mill.scala")
    def isMill: Boolean =
      isMillBuild || filename == "build.sc"
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
      toLanguage == Language.SCALA && isFile
    }
    def isJava: Boolean = {
      toLanguage == Language.JAVA && isFile
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

    def startWith(other: AbsolutePath): Boolean =
      path.toNIO.startsWith(other.toNIO)

    def createDirectories(): AbsolutePath =
      AbsolutePath(Files.createDirectories(path.dealias.toNIO))

    def writeText(text: String): Unit = {
      path.parent.createDirectories()
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
          path.toNIO,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      } catch {
        case NonFatal(_) =>
          Files.move(tmp, path.toNIO, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  implicit class XtensionSymbolInformationKind(kind: s.SymbolInformation.Kind) {
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

    def isRelevantKind: Boolean = {
      kind match {
        case k.OBJECT | k.PACKAGE_OBJECT | k.CLASS | k.TRAIT | k.INTERFACE |
            k.METHOD | k.TYPE =>
          true
        case _ => false
      }
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

    def safeParse[T <: m.Tree](dialect: m.Dialect)(implicit
        parse: m.parsers.Parse[T]
    ): m.parsers.Parsed[T] =
      try {
        parse(input, dialect)
      } catch {
        case t: InvariantFailedException =>
          m.parsers.Parsed.Error(Position.None, t.toString(), t)
      }
  }

  implicit class XtensionSymbolInformation(info: s.SymbolInformation) {
    // This works only for SymbolInformation produced in metals in `ScalaTopLevelMtags`.
    def isExtension: Boolean = (EXTENSION & info.properties) != 0

  }

  val EXTENSION: Int = s.SymbolInformation.Property.values.map(_.value).max << 1

  implicit class XtensionWorkspaceSymbolQuery(
      query: m.internal.metals.WorkspaceSymbolQuery
  ) {
    def matches(info: s.SymbolInformation): Boolean = {
      info.kind.isRelevantKind && query.matches(info.symbol)
    }

  }

}
