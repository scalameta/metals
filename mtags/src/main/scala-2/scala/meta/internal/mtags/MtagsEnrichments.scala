package scala.meta.internal.mtags

import java.net.URI
import java.net.URLDecoder
import java.nio.file.Paths
import java.util.concurrent.CancellationException

import scala.collection.mutable
import scala.reflect.internal.util.Position
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.{meta => m}

import scala.meta.internal.semanticdb.SymbolInformation.{Property => p}
import scala.meta.io.AbsolutePath
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

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

  implicit class XtensionURIMtags(value: URI) {
    def toAbsolutePath: AbsolutePath = toAbsolutePath(followSymlink = true)
    def toAbsolutePath(followSymlink: Boolean): AbsolutePath = {
      val path =
        if (value.getScheme() == "jar")
          Try {
            AbsolutePath(Paths.get(value))
          } match {
            case Success(path) => path
            case Failure(_) =>
              // don't close - put up with the resource staying open so all AbsolutePath methods don't have to be wrapped
              m.internal.io.PlatformFileIO.newFileSystem(
                value,
                new java.util.HashMap[String, String]()
              )
              AbsolutePath(Paths.get(value))
          }
        else
          AbsolutePath(Paths.get(value))
      if (followSymlink)
        path.dealias
      else
        path
    }
  }

  implicit class XtensionStringMtags(value: String) {

    def stripBackticks: String = value.stripPrefix("`").stripSuffix("`")
    def isBackticked: Boolean =
      value.size > 1 && value.head == '`' && value.last == '`'
    def toAbsolutePath: AbsolutePath = toAbsolutePath(true)
    def toAbsolutePath(followSymlink: Boolean): AbsolutePath = {
      // jar schemes must have "jar:file:"" instead of "jar:file%3A" or jar file system won't recognise the URI.
      // but don't overdecode as URIs may not be recognised e.g. "com-microsoft-java-debug-core-0.32.0%2B1.jar" is correct
      if (value.toUpperCase.startsWith("JAR%3AFILE"))
        URLDecoder.decode(value, "UTF-8").toAbsolutePath(followSymlink)
      else if (value.toUpperCase.startsWith("JAR:FILE%3A"))
        URLDecoder.decode(value, "UTF-8").toAbsolutePath(followSymlink)
      else if (value.toUpperCase.startsWith("JAR"))
        URI.create(value).toAbsolutePath(followSymlink)
      else {
        val stripped = value.stripPrefix("metals:")
        val percentEncoded = URIEncoderDecoder.encode(stripped)
        URI.create(percentEncoded).toAbsolutePath(followSymlink)
      }
    }
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

  implicit class XtensionRangeLspInverse(range: l.Range) {
    def toLocation(uri: URI): l.Location = new l.Location(uri.toString(), range)
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

  implicit class XtensionPosition(pos: Position) {
    def encloses(other: Position): Boolean =
      pos.start <= other.start && pos.end >= other.end

    def encloses(other: RangeParams): Boolean =
      pos.start <= other.offset() && pos.end >= other.endOffset()
  }

  implicit class XtensionRangeParameters(pos: RangeParams) {
    def encloses(other: Position): Boolean =
      pos.offset <= other.start && pos.endOffset >= other.end
  }

}
