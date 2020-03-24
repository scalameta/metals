package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.{lsp4j => l}
import scala.collection.convert.DecorateAsJava
import scala.collection.convert.DecorateAsScala
import scala.compat.java8.FutureConverters
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Promise
import scala.meta.Tree
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.MtagsEnrichments
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.trees.Origin
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token
import scala.util.Properties
import scala.{meta => m}
import java.nio.file.StandardOpenOption
import scala.util.control.NonFatal
import scala.util.Try

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
 *  import scala.meta.internal.jdk.CollectionConverters._
 * }}}
 *
 * If this doesn't scale because we have too many unrelated extension methods
 * then we can split this up, but for now it's really convenient to have to
 * remember only one import.
 */
object MetalsEnrichments
    extends DecorateAsJava
    with DecorateAsScala
    with MtagsEnrichments {

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

  implicit class XtensionCompileResult(result: b.CompileResult) {
    def isOK: Boolean = result.getStatusCode == b.StatusCode.OK
    def isError: Boolean = result.getStatusCode == b.StatusCode.ERROR
  }

  implicit class XtensionEditDistance(result: Either[EmptyResult, m.Position]) {
    def toPosition(dirty: l.Position): Option[l.Position] =
      foldResult(
        onPosition =
          pos => Some(new l.Position(pos.startLine, pos.startColumn)),
        onUnchanged = () => Some(dirty),
        onNoMatch = () => None
      )
    def toLocation(dirty: l.Location): Option[l.Location] =
      foldResult(
        pos => {
          Some(
            new l.Location(
              dirty.getUri,
              new l.Range(
                new l.Position(pos.startLine, pos.startColumn),
                new l.Position(pos.endLine, pos.endColumn)
              )
            )
          )
        },
        () => Some(dirty),
        () => None
      )
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

    def withTimeout(length: Int, unit: TimeUnit)(
        implicit ec: ExecutionContext
    ): Future[A] = {
      Future(Await.result(future, FiniteDuration(length, unit)))
    }

    def onTimeout(length: Int, unit: TimeUnit)(
        action: => Unit
    )(implicit ec: ExecutionContext): Future[A] = {
      // schedule action to execute on timeout
      future.withTimeout(length, unit).recoverWith {
        case e: TimeoutException =>
          action
          Future.failed(e)
      }
    }

    def liftOption(
        implicit ec: ExecutionContext
    ): Future[Option[A]] = future.map(Some(_))
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
        s.getChildren.forEach { child => loop(child, newOwner) }
      }
      symbol.foreach { s => loop(s, Symbols.RootPackage) }
      buf.result()
    }
  }

  implicit class XtensionPath(path: Path) {
    def toUriInput: Input.VirtualFile = {
      val uri = path.toUri.toString
      val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      Input.VirtualFile(uri, text)
    }
    def isSemanticdb: Boolean =
      path.getFileName.toString.endsWith(".semanticdb")
  }
  implicit class XtensionAbsolutePathBuffers(path: AbsolutePath) {

    def sourcerootOption: String = s""""-P:semanticdb:sourceroot:$path""""

    /**
     * Resolve each path segment individually to prevent FileSystem mismatch errors.
     */
    def resolveZipPath(zipPath: Path): AbsolutePath = {
      zipPath.iterator().asScala.foldLeft(path) {
        case (accum, filename) =>
          accum.resolve(filename.toString)
      }
    }
    def isDependencySource(workspace: AbsolutePath): Boolean =
      isLocalFileSystem(workspace) &&
        isInReadonlyDirectory(workspace)

    def isWorkspaceSource(workspace: AbsolutePath): Boolean =
      isLocalFileSystem(workspace) &&
        !isInReadonlyDirectory(workspace) &&
        path.toNIO.startsWith(workspace.toNIO)

    def isLocalFileSystem(workspace: AbsolutePath): Boolean =
      workspace.toNIO.getFileSystem == path.toNIO.getFileSystem

    def isInReadonlyDirectory(workspace: AbsolutePath): Boolean =
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

    /**
     * Reads file contents from editor buffer with fallback to disk.
     */
    def toInputFromBuffers(buffers: Buffers): Input.VirtualFile = {
      buffers.get(path) match {
        case Some(text) => Input.VirtualFile(path.toString(), text)
        case None => path.toInput
      }
    }

    // Using [[Files.isSymbolicLink]] is not enough.
    // It will be false when one of the parents is a symlink (e.g. /dir/link/file.txt)
    def dealias: AbsolutePath = {
      if (path.exists) { // cannot dealias non-existing path
        AbsolutePath(path.toNIO.toRealPath())
      } else {
        path
      }
    }

    def touch(): Unit = {
      if (!path.exists) {
        path.parent.createDirectories()
        Files.createFile(path.toNIO)
      }
    }

    def createDirectories(): AbsolutePath = {
      AbsolutePath(Files.createDirectories(dealias.toNIO))
    }

    def delete(): Unit = {
      Files.delete(dealias.toNIO)
    }

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

    def appendText(text: String): Unit = {
      path.parent.createDirectories()
      Files.write(
        path.toNIO,
        text.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.APPEND
      )
    }

  }

  implicit class XtensionString(value: String) {

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

    def lastIndexBetween(
        char: Char,
        lowerBound: Int = 0,
        upperBound: Int = value.size
    ): Int = {
      var index = upperBound
      while (index >= lowerBound && value(index) != char) {
        index -= 1
      }
      index
    }

    def toAbsolutePathSafe: Option[AbsolutePath] = Try(toAbsolutePath).toOption

    def toAbsolutePath: AbsolutePath =
      AbsolutePath(
        Paths.get(URI.create(value.stripPrefix("metals:")))
      ).dealias
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
        .map { occ => occ.toLocation(uri) }
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

  implicit class XtensionSymbolOccurrenceProtocol(occ: s.SymbolOccurrence) {
    def toLocation(uri: String): l.Location = {
      occ.range.getOrElse(s.Range(0, 0, 0, 0)).toLocation(uri)
    }
    def encloses(
        pos: l.Position,
        includeLastCharacter: Boolean = false
    ): Boolean =
      occ.range.isDefined &&
        occ.range.get.encloses(pos, includeLastCharacter)
  }

  implicit class XtensionDiagnosticBsp(diag: b.Diagnostic) {
    def toLSP: l.Diagnostic =
      new l.Diagnostic(
        diag.getRange.toLSP,
        fansi.Str(diag.getMessage).plainText,
        diag.getSeverity.toLSP,
        if (diag.getSource == null) "scalac" else diag.getSource
        // We omit diag.getCode since Bloop's BSP implementation uses 'code' with different semantics
        // than LSP. See https://github.com/scalacenter/bloop/issues/1134 for details
      )
  }

  implicit class XtensionHttpExchange(exchange: HttpServerExchange) {
    def getQuery(key: String): Option[String] =
      Option(exchange.getQueryParameters.get(key)).flatMap(_.asScala.headOption)
  }
  implicit class XtensionScalacOptions(item: b.ScalacOptionsItem) {
    def classpath: Iterator[AbsolutePath] = {
      item.getClasspath.asScala.iterator
        .map(uri => AbsolutePath(Paths.get(URI.create(uri))))
        .filter(p => Files.exists(p.toNIO))
    }
    def targetroot: AbsolutePath = {
      semanticdbFlag("targetroot")
        .map(AbsolutePath(_))
        .getOrElse(item.getClassDirectory.toAbsolutePath)
    }
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
    def supportsHierarchicalDocumentSymbols: Boolean =
      (for {
        params <- initializeParams
        capabilities <- Option(params.getCapabilities)
        textDocument <- Option(capabilities.getTextDocument)
        documentSymbol <- Option(textDocument.getDocumentSymbol)
        hierarchicalDocumentSymbolSupport <- Option(
          documentSymbol.getHierarchicalDocumentSymbolSupport
        )
      } yield hierarchicalDocumentSymbolSupport.booleanValue).getOrElse(false)

    def supportsCompletionSnippets: Boolean =
      (for {
        params <- initializeParams
        capabilities <- Option(params.getCapabilities)
        textDocument <- Option(capabilities.getTextDocument)
        completion <- Option(textDocument.getCompletion)
        completionItem <- Option(completion.getCompletionItem)
        snippetSupport <- Option(completionItem.getSnippetSupport())
      } yield snippetSupport.booleanValue).getOrElse(false)

    def supportsCodeActionLiterals: Boolean =
      (for {
        params <- initializeParams
        capabilities <- Option(params.getCapabilities)
        textDocument <- Option(capabilities.getTextDocument)
        codeAction <- Option(textDocument.getCodeAction)
        literalSupport <- Option(codeAction.getCodeActionLiteralSupport())
      } yield literalSupport).isDefined

  }

  implicit class XtensionPromise[T](promise: Promise[T]) {
    def cancel(): Unit =
      promise.tryFailure(new CancellationException())
  }

  implicit class XtensionToken(token: Token) {
    def isWhiteSpaceOrComment: Boolean = token match {
      case _: Token.Space | _: Token.Tab | _: Token.CR | _: Token.LF |
          _: Token.LFLF | _: Token.FF | _: Token.Comment | _: Token.BOF |
          _: Token.EOF =>
        true
      case _ => false
    }
  }

  implicit class XtensionTreeTokenStream(tree: Tree) {
    def leadingTokens: Iterator[Token] = tree.origin match {
      case Origin.Parsed(input, _, pos) =>
        val tokens = input.tokenize.get
        tokens.slice(0, pos.start - 1).reverseIterator
      case _ => Iterator.empty
    }

    def trailingTokens: Iterator[Token] = tree.origin match {
      case Origin.Parsed(input, _, pos) =>
        val tokens = input.tokenize.get
        tokens.slice(pos.end + 1, tokens.length).iterator
      case _ => Iterator.empty
    }

    def findFirstLeading(predicate: Token => Boolean): Option[Token] =
      leadingTokens.find(predicate)

    def findFirstTrailing(predicate: Token => Boolean): Option[Token] =
      trailingTokens.find(predicate)
  }

  implicit class OptionFutureTransformer[A](state: Future[Option[A]]) {
    def flatMapOption[B](
        f: A => Future[Option[B]]
    )(implicit ec: ExecutionContext): Future[Option[B]] =
      state.flatMap(_.fold(Future.successful(Option.empty[B]))(f))

    def mapOption[B](
        f: A => Future[B]
    )(implicit ec: ExecutionContext): Future[Option[B]] =
      state.flatMap(
        _.fold(Future.successful(Option.empty[B]))(f(_).liftOption)
      )
  }

}
