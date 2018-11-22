package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.Gson
import com.google.gson.JsonElement
import io.undertow.server.HttpServerExchange
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.{lsp4j => l}
import scala.collection.convert.DecorateAsJava
import scala.collection.convert.DecorateAsScala
import scala.compat.java8.FutureConverters
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.util.Properties
import scala.util.Try
import scala.{meta => m}

/**
 * One stop shop for all extension methods that are used in the metals build.
 *
 * Usage: {{{
 *   import scala.meta.internal.metals.MetalsEnrichments._
 *   List(1).asJava
 *   Future(1).asJava
 *   // ...
 * }}}
 *
 * Includes the following converters from the standard library: {{{
 *  import scala.compat.java8.FutureConverters._
 *  import scala.collection.JavaConverters._
 * }}}
 *
 * If this doesn't scale because we have too many unrelated extension methods
 * then we can split this up, but for now it's really convenient to have to
 * remember only one import.
 */
object MetalsEnrichments extends DecorateAsJava with DecorateAsScala {

  implicit class XtensionBuildTarget(buildTarget: b.BuildTarget) {

    /**
     * Reads BSP `BuildTarget.data` field into a `ScalaBuildTarget`.
     */
    def asScalaBuildTarget: Option[b.ScalaBuildTarget] = {
      for {
        data <- Option(buildTarget.getData)
        if data.isInstanceOf[JsonElement]
        info <- Try(
          new Gson().fromJson[b.ScalaBuildTarget](
            data.asInstanceOf[JsonElement],
            classOf[b.ScalaBuildTarget]
          )
        ).toOption
      } yield info
    }

  }

  implicit class XtensionEditDistance(result: Either[EmptyResult, m.Position]) {
    def foldResult[B](
        onPosition: m.Position => B,
        onUnchanged: () => B,
        onNoMatch: () => B
    ): B = result match {
      case Right(pos) => onPosition(pos)
      case Left(EmptyResult.Unchanged) => onUnchanged()
      case Left(EmptyResult.NoMatch) => onNoMatch()
    }
  }

  implicit class XtensionJavaFuture[T](future: CompletionStage[T]) {
    def asScala: Future[T] = FutureConverters.toScala(future)
  }

  implicit class XtensionScalaFuture[A](future: Future[A]) {
    def asJava: CompletableFuture[A] =
      FutureConverters.toJava(future).toCompletableFuture
    def asJavaUnit(implicit ec: ExecutionContext): CompletableFuture[Unit] =
      future.ignoreValue.asJava

    /**
     * Registers this future to be tracked by the client status bar.
     */
    def trackInStatusBar(
        message: String,
        showDots: Boolean = true
    )(implicit statusBar: StatusBar): Future[A] = {
      statusBar.addFuture(
        message,
        future,
        maxDots = if (showDots) Int.MaxValue else -1
      )
      future
    }
    def ignoreValue(implicit ec: ExecutionContext): Future[Unit] =
      future.map(_ => ())
    def logErrorAndContinue(
        doingWhat: String
    )(implicit ec: ExecutionContext): Future[Unit] = {
      future.ignoreValue.recover {
        case e =>
          scribe.error(s"Unexpected error while $doingWhat", e)
      }
    }
    def logError(
        doingWhat: String
    )(implicit ec: ExecutionContext): Future[A] = {
      future.recover {
        case e =>
          scribe.error(s"Unexpected error while $doingWhat", e)
          throw e
      }
    }
  }

  implicit class XtensionJavaList[A](lst: util.List[A]) {
    def map[B](fn: A => B): util.List[B] = {
      val out = new util.ArrayList[B]()
      val iter = lst.iterator()
      while (iter.hasNext) {
        out.add(fn(iter.next()))
      }
      out
    }
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

  implicit class XtensionAbsolutePathBuffers(path: AbsolutePath) {

    /**
     * Resolve each path segment individually to prevent jjkjjk
     */
    def resolveZipPath(zipPath: Path): AbsolutePath = {
      zipPath.iterator().asScala.foldLeft(path) {
        case (accum, filename) =>
          accum.resolve(filename.toString)
      }
    }
    def isDependencySource(workspace: AbsolutePath): Boolean =
      workspace.toNIO.getFileSystem == path.toNIO.getFileSystem &&
        path.toNIO.startsWith(
          workspace.resolve(Directories.readonly).toNIO
        )

    /**
     * Writes zip file contents to disk under $workspace/.metals/readonly.
     */
    def toFileOnDisk(workspace: AbsolutePath): AbsolutePath = {
      if (path.toNIO.getFileSystem == workspace.toNIO.getFileSystem) {
        path
      } else {
        val readonly = workspace.resolve(Directories.readonly)
        val out = readonly.resolveZipPath(path.toNIO).toNIO
        Files.createDirectories(out.getParent)
        if (!Properties.isWin && Files.isRegularFile(out)) {
          out.toFile.setWritable(true)
        }
        try {
          Files.copy(path.toNIO, out, StandardCopyOption.REPLACE_EXISTING)
          // Don't use readOnly files on Windows, makes it impossible to walk
          // the entire directory later on.
          if (!Properties.isWin) {
            out.toFile.setReadOnly()
          }
        } catch {
          case _: FileAlreadyExistsException =>
            () // ignore
        }
        AbsolutePath(out)
      }
    }

    def toTextDocumentIdentifier: TextDocumentIdentifier = {
      new TextDocumentIdentifier(path.toURI.toString)
    }

    def readText: String = {
      FileIO.slurp(path, StandardCharsets.UTF_8)
    }

    def isJar: Boolean = {
      val filename = path.toNIO.getFileName.toString
      filename.endsWith(".jar")
    }

    def isSbtRelated(workspace: AbsolutePath): Boolean = {
      val isToplevel = Set(workspace.toNIO, workspace.toNIO.resolve("project"))
      isToplevel(path.toNIO.getParent) && {
        val filename = path.toNIO.getFileName.toString
        filename.endsWith("build.properties") ||
        filename.endsWith(".sbt") ||
        filename.endsWith(".scala")
      }
    }

    /**
     * Reads file contents from editor buffer with fallback to disk.
     */
    def toInputFromBuffers(buffers: Buffers): Input.VirtualFile = {
      buffers.get(path) match {
        case Some(text) => Input.VirtualFile(path.toString(), text)
        case None => path.toInput
      }
    }
  }

  implicit class XtensionStringUriProtocol(value: String) {

    /** Returns true if this is a Scala.js or Scala Native target
     *
     * FIXME: https://github.com/scalacenter/bloop/issues/700
     */
    def isNonJVMPlatformOption: Boolean = {
      def isCompilerPlugin(name: String, organization: String): Boolean = {
        value.startsWith("-Xplugin:") &&
        value.contains(name) &&
        value.contains(organization)
      }
      // Scala Native and Scala.js are not needed to navigate dependency sources
      isCompilerPlugin("nscplugin", "scala-native") ||
      isCompilerPlugin("scalajs-compiler", "scala-js") ||
      value.startsWith("-P:scalajs:")
    }

    def toAbsolutePath: AbsolutePath =
      AbsolutePath(Paths.get(URI.create(value.stripPrefix("metals:"))))
  }

  implicit class XtensionTextDocumentSemanticdb(textDocument: s.TextDocument) {

    /** Returns true if the symbol is defined in this document */
    def definesSymbol(symbol: String): Boolean = {
      textDocument.occurrences.exists { localOccurrence =>
        localOccurrence.role.isDefinition &&
        localOccurrence.symbol == symbol
      }
    }

    def toInput: Input = {
      Input.VirtualFile(textDocument.uri, textDocument.text)
    }
    def definition(uri: String, symbol: String): Option[l.Location] = {
      textDocument.occurrences
        .find(o => o.role.isDefinition && o.symbol == symbol)
        .map { occ =>
          occ.toLocation(uri)
        }
    }
  }

  implicit class XtensionSeverityBsp(sev: b.DiagnosticSeverity) {
    def toLSP: l.DiagnosticSeverity =
      l.DiagnosticSeverity.forValue(sev.getValue)
  }

  implicit class XtensionPositionBSp(pos: b.Position) {
    def toLSP: l.Position =
      new l.Position(pos.getLine, pos.getCharacter)
  }

  implicit class XtensionRangeBsp(range: b.Range) {
    def toLSP: l.Range =
      new l.Range(range.getStart.toLSP, range.getEnd.toLSP)
  }

  implicit class XtensionRangeBuildProtocol(range: s.Range) {
    def toLSP: l.Range = {
      val start = new l.Position(range.startLine, range.startCharacter)
      val end = new l.Position(range.endLine, range.endCharacter)
      new l.Range(start, end)
    }
    def encloses(other: l.Position): Boolean = {
      range.startLine <= other.getLine &&
      range.endLine >= other.getLine &&
      range.startCharacter <= other.getCharacter &&
      range.endCharacter > other.getCharacter
    }
    def encloses(other: l.Range): Boolean = {
      encloses(other.getStart) &&
      encloses(other.getEnd)
    }
  }

  implicit class XtensionSymbolOccurrenceProtocol(occ: s.SymbolOccurrence) {
    def toLocation(uri: String): l.Location = {
      val range = occ.range.getOrElse(s.Range(0, 0, 0, 0)).toLSP
      new l.Location(uri, range)
    }
    def encloses(pos: l.Position): Boolean =
      occ.range.isDefined &&
        occ.range.get.encloses(pos)
  }

  implicit class XtensionDiagnosticBsp(diag: b.Diagnostic) {
    def toLSP: l.Diagnostic =
      new l.Diagnostic(
        diag.getRange.toLSP,
        diag.getMessage,
        diag.getSeverity.toLSP,
        diag.getSource,
        diag.getCode
      )
  }

  implicit class XtensionHttpExchange(exchange: HttpServerExchange) {
    def getQuery(key: String): Option[String] =
      Option(exchange.getQueryParameters.get(key)).flatMap(_.asScala.headOption)
  }
  implicit class XtensionScalacOptions(item: b.ScalacOptionsItem) {
    def isJVM: Boolean = {
      // FIXME: https://github.com/scalacenter/bloop/issues/700
      !item.getOptions.asScala.exists(_.isNonJVMPlatformOption)
    }

    /** Returns the value of a -P:semanticdb:$option:$value compiler flag. */
    def semanticdbFlag(name: String): Option[String] = {
      val flag = s"-P:semanticdb:$name:"
      item.getOptions.asScala
        .find(_.startsWith(flag))
        .map(_.stripPrefix(flag))
    }

  }

}
