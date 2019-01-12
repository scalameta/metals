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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import scala.collection.convert.DecorateAsJava
import scala.collection.convert.DecorateAsScala
import scala.compat.java8.FutureConverters
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.util.Properties
import scala.util.control.NonFatal
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

  private def decodeJson[T](obj: AnyRef, cls: Class[T]): Option[T] =
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
          scribe.error(s"decode error: $cls", e)
          None
      }
    } yield value

  implicit class XtensionBuildTarget(buildTarget: b.BuildTarget) {
    def asScalaBuildTarget: Option[b.ScalaBuildTarget] = {
      decodeJson(buildTarget.getData, classOf[b.ScalaBuildTarget])
    }
  }
  implicit class XtensionTaskStart(task: b.TaskStartParams) {
    def asCompileTask: Option[b.CompileTask] = {
      decodeJson(task.getData, classOf[b.CompileTask])
    }
  }

  implicit class XtensionTaskFinish(task: b.TaskFinishParams) {
    def asCompileReport: Option[b.CompileReport] = {
      decodeJson(task.getData, classOf[b.CompileReport])
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
    def asCancelable: CancelableFuture[A] =
      CancelableFuture(future)
    def asJava: CompletableFuture[A] =
      FutureConverters.toJava(future).toCompletableFuture
    def asJavaObject: CompletableFuture[Object] =
      future.asJava.asInstanceOf[CompletableFuture[Object]]
    def asJavaUnit(implicit ec: ExecutionContext): CompletableFuture[Unit] =
      future.ignoreValue.asJava

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

  implicit class XtensionDocumentSymbol(symbol: Seq[l.DocumentSymbol]) {

    def toSymbolInformation(uri: String): List[l.SymbolInformation] = {
      val buf = List.newBuilder[l.SymbolInformation]
      def loop(s: l.DocumentSymbol, owner: String): Unit = {
        buf += new l.SymbolInformation(
          s.getName,
          s.getKind,
          new l.Location(uri, s.getRange),
          if (owner == Symbols.RootPackage) "" else owner
        )
        val newOwner: String = s.getKind match {
          case l.SymbolKind.Package =>
            s.getName.split("\\.").foldLeft(owner) {
              case (accum, name) =>
                Symbols.Global(accum, Descriptor.Package(name))
            }
          case l.SymbolKind.Class | l.SymbolKind.Interface =>
            Symbols.Global(owner, Descriptor.Type(s.getName))
          case _ =>
            Symbols.Global(owner, Descriptor.Term(s.getName))
        }
        s.getChildren.forEach { child =>
          loop(child, newOwner)
        }
      }
      symbol.foreach { s =>
        loop(s, Symbols.RootPackage)
      }
      buf.result()
    }
  }

  implicit class XtensionAbsolutePathBuffers(path: AbsolutePath) {

    def sourcerootOption: String = s""""-P:semanticdb:sourceroot:$path""""

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

    def dealias: AbsolutePath =
      if (Files.isSymbolicLink(path.toNIO)) {
        AbsolutePath(Files.readSymbolicLink(path.toNIO))
      } else {
        path
      }

    def createDirectories(): AbsolutePath = {
      AbsolutePath(Files.createDirectories(dealias.toNIO))
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

  implicit class XtensionDiagnosticLSP(d: l.Diagnostic) {
    def formatMessage(uri: String, hint: String): String = {
      val severity = d.getSeverity.toString.toLowerCase()
      s"$severity:$hint $uri:${d.getRange.getStart.getLine} ${d.getMessage}"
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
    def toMeta(input: m.Input): m.Position =
      m.Position.Range(
        input,
        range.getStart.getLine,
        range.getStart.getCharacter,
        range.getEnd.getLine,
        range.getEnd.getCharacter
      )
    def toLSP: l.Range =
      new l.Range(range.getStart.toLSP, range.getEnd.toLSP)
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
        fansi.Str(diag.getMessage).plainText,
        diag.getSeverity.toLSP,
        if (diag.getSource == null) "scalac" else diag.getSource,
        diag.getCode
      )
  }

  implicit class XtensionHttpExchange(exchange: HttpServerExchange) {
    def getQuery(key: String): Option[String] =
      Option(exchange.getQueryParameters.get(key)).flatMap(_.asScala.headOption)
  }
  implicit class XtensionScalacOptions(item: b.ScalacOptionsItem) {
    def isSemanticdbEnabled: Boolean =
      item.getOptions.asScala.exists { opt =>
        opt.startsWith("-Xplugin:") && opt
          .contains("semanticdb-scalac")
      }
    def isSourcerootDeclared: Boolean = {
      item.getOptions.asScala.exists { option =>
        option.startsWith("-P:semanticdb:sourceroot")
      }
    }
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

  implicit class XtensionClientCapabilities(
      initializeParams: Option[l.InitializeParams]
  ) {
    val supportsHierarchicalDocumentSymbols =
      (for {
        params <- initializeParams
        capabilities <- Option(params.getCapabilities)
        textDocument <- Option(capabilities.getTextDocument)
        documentSymbol <- Option(textDocument.getDocumentSymbol)
        hierarchicalDocumentSymbolSupport <- Option(
          documentSymbol.getHierarchicalDocumentSymbolSupport
        )
      } yield hierarchicalDocumentSymbolSupport.booleanValue).getOrElse(false)

  }

  implicit class XtensionPromise[T](promise: Promise[T]) {
    def cancel(): Unit =
      promise.tryFailure(new CancellationException())
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
}
