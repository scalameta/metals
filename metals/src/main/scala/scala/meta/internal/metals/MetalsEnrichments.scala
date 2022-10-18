package scala.meta.internal.metals

import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.annotation.tailrec
import scala.collection.convert.AsJavaExtensions
import scala.collection.convert.AsScalaExtensions
import scala.collection.mutable
import scala.compat.java8.FutureConverters
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Properties
import scala.util.Try
import scala.util.control.NonFatal
import scala.{meta => m}

import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.MtagsEnrichments
import scala.meta.internal.parsing.EmptyResult
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.trees.Origin
import scala.meta.internal.trees.Origin.Parsed
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

import ch.epfl.scala.{bsp4j => b}
import fansi.ErrorMode
import io.undertow.server.HttpServerExchange
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.{lsp4j => l}

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
    extends AsJavaExtensions
    with AsScalaExtensions
    with MtagsEnrichments {

  implicit class XtensionBuildTarget(buildTarget: b.BuildTarget) {

    def isSbtBuild: Boolean = dataKind == "sbt"

    def baseDirectory: String =
      Option(buildTarget.getBaseDirectory()).getOrElse("")

    def dataKind: String = Option(buildTarget.getDataKind()).getOrElse("")

    def asScalaBuildTarget: Option[b.ScalaBuildTarget] = {
      asSbtBuildTarget
        .map(_.getScalaBuildTarget)
        .orElse(decodeJson(buildTarget.getData, classOf[b.ScalaBuildTarget]))
    }

    def asSbtBuildTarget: Option[b.SbtBuildTarget] = {
      if (isSbtBuild)
        decodeJson(buildTarget.getData, classOf[b.SbtBuildTarget])
      else
        None
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

  implicit class XtensionStatusCode(code: b.StatusCode) {
    def isOK: Boolean = code == b.StatusCode.OK
    def isError: Boolean = code == b.StatusCode.ERROR
  }

  implicit class XtensionCompileResult(result: b.CompileResult) {
    def isOK: Boolean = result.getStatusCode.isOK
    def isError: Boolean = result.getStatusCode.isError
  }

  implicit class XtensionEditDistance(result: Either[EmptyResult, m.Position]) {
    def toPosition(dirty: l.Position): Option[l.Position] =
      foldResult(
        onPosition =
          pos => Some(new l.Position(pos.startLine, pos.startColumn)),
        onUnchanged = () => Some(dirty),
        onNoMatch = () => None,
      )

    def toLocation(dirty: l.Location): Option[l.Location] =
      foldResult(
        pos => {
          Some(
            new l.Location(
              dirty.getUri,
              new l.Range(
                new l.Position(pos.startLine, pos.startColumn),
                new l.Position(pos.endLine, pos.endColumn),
              ),
            )
          )
        },
        () => Some(dirty),
        () => None,
      )

    def foldResult[B](
        onPosition: m.Position => B,
        onUnchanged: () => B,
        onNoMatch: () => B,
    ): B =
      result match {
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

    def withObjectValue: Future[Object] =
      future.asInstanceOf[Future[Object]]

    def logErrorAndContinue(
        doingWhat: String
    )(implicit ec: ExecutionContext): Future[Unit] = {
      future.ignoreValue.recover { case e =>
        scribe.error(s"Unexpected error while $doingWhat", e)
      }
    }

    def logError(
        doingWhat: String
    )(implicit ec: ExecutionContext): Future[A] = {
      future.recover { case e =>
        scribe.error(s"Unexpected error while $doingWhat", e)
        throw e
      }
    }

    def withTimeout(length: Int, unit: TimeUnit)(implicit
        ec: ExecutionContext
    ): Future[A] = {
      Future(Await.result(future, FiniteDuration(length, unit)))
    }

    def onTimeout(length: Int, unit: TimeUnit)(
        action: => Unit
    )(implicit ec: ExecutionContext): Future[A] = {
      // schedule action to execute on timeout
      future.withTimeout(length, unit).recoverWith { case e: TimeoutException =>
        action
        Future.failed(e)
      }
    }

    def liftOption(implicit
        ec: ExecutionContext
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

  implicit class XtensionList[T](lst: List[T]) {
    def acceptFirst[R](
        accept: T => Option[List[R]]
    ): List[R] = {
      @tailrec
      def loop(toCheck: List[T]): List[R] = {
        toCheck match {
          case prioritized :: rest =>
            val contributed = accept(prioritized)
            contributed match {
              case Some(edits) => edits
              case None => loop(rest)
            }
          case Nil => Nil
        }
      }

      loop(lst)
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
          if (owner == Symbols.RootPackage) "" else owner,
        )
        val newOwner: String = s.getKind match {
          case l.SymbolKind.Package =>
            s.getName.split("\\.").foldLeft(owner) { case (accum, name) =>
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
      val uri = path.toAbsolutePath.toUri.toString
      val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      Input.VirtualFile(uri, text)
    }

    def isSemanticdb: Boolean =
      path.getFileName.toString.endsWith(".semanticdb")
  }

  implicit class XtensionAbsolutePathBuffers(path: AbsolutePath) {

    def scalaSourcerootOption: String = s""""-P:semanticdb:sourceroot:$path""""

    def javaSourcerootOption: String =
      s""""-Xplugin:semanticdb -sourceroot:$path""""

    /**
     * Resolve each path segment individually to prevent FileSystem mismatch errors.
     */
    def resolveZipPath(zipPath: Path): AbsolutePath = {
      zipPath.iterator().asScala.foldLeft(path) { case (accum, filename) =>
        accum.resolve(filename.toString)
      }
    }

    def isDependencySource(workspace: AbsolutePath): Boolean = {
      (isLocalFileSystem(workspace) &&
        isInReadonlyDirectory(workspace)) || isJarFileSystem
    }

    def isWorkspaceSource(workspace: AbsolutePath): Boolean =
      isLocalFileSystem(workspace) &&
        !isInReadonlyDirectory(workspace) &&
        path.toNIO.startsWith(workspace.toNIO)

    def isLocalFileSystem(workspace: AbsolutePath): Boolean =
      workspace.toNIO.getFileSystem == path.toNIO.getFileSystem

    def isJarFileSystem: Boolean =
      path.toNIO.getFileSystem().provider().getScheme().equals("jar")

    def isInReadonlyDirectory(workspace: AbsolutePath): Boolean =
      path.toNIO.startsWith(
        workspace.resolve(Directories.readonly).toNIO
      )

    def toRelativeInside(prefix: AbsolutePath): Option[RelativePath] = {
      // windows throws an exception on toRelative when on different drives
      if (path.toNIO.getRoot() != prefix.toNIO.getRoot())
        None
      else {
        val relative = path.toRelative(prefix)
        if (relative.toNIO.getName(0).filename != "..") Some(relative)
        else None
      }
    }

    def isInside(prefix: AbsolutePath): Boolean =
      toRelativeInside(prefix).isDefined

    /**
     * Writes zip file contents to disk under $workspace/.metals/readonly.
     *
     * In case if path is jar then target directory is $workspace/.metals/readonly/dependencies/$jarName
     */
    def toFileOnDisk(workspace: AbsolutePath): AbsolutePath =
      toFileOnDisk0(workspace, 0)

    private def toFileOnDisk0(
        workspace: AbsolutePath,
        retryCount: Int,
    ): AbsolutePath = {
      def toJarMeta(jar: AbsolutePath): String = {
        val time = Files.getLastModifiedTime(jar.toNIO).toMillis()
        s"$time\n${jar.toNIO}"
      }

      def readJarMeta(jarMetaFile: AbsolutePath): Option[String] = {
        if (jarMetaFile.exists)
          Some(FileIO.slurp(jarMetaFile, StandardCharsets.UTF_8))
        else None
      }

      def withJarDirLock[A](dir: AbsolutePath)(f: => A)(fallback: => A): A = {
        if (!dir.exists) Files.createDirectories(dir.toNIO)
        val lockFile = dir.resolve(".lock")
        if (lockFile.exists) {
          fallback
        } else {
          try {
            Files.createFile(lockFile.toNIO)
            f
          } catch {
            case _: IOException =>
              fallback
          } finally {
            Files.deleteIfExists(lockFile.toNIO)
          }
        }
      }

      def retry: AbsolutePath = {
        Thread.sleep(50)
        this.toFileOnDisk0(workspace, retryCount + 1)
      }

      def copyFile(path: AbsolutePath, to: AbsolutePath): AbsolutePath = {
        val out = to.toNIO
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

      // prevent infinity loop
      if (retryCount > 5) {
        throw new Exception(s"Unable to save $path in workspace")
      } else if (path.toNIO.getFileSystem == workspace.toNIO.getFileSystem) {
        path
      } else {
        path.jarPath match {
          case Some(jar) =>
            val jarDir =
              workspace.resolve(Directories.dependencies).resolve(jar.filename)
            val out = jarDir.resolveZipPath(path.toNIO)
            val jarMetaFile = jarDir.resolve(".jar.meta")

            lazy val currentJarMeta = readJarMeta(jarMetaFile)
            lazy val jarMeta = toJarMeta(jar)

            val updateMeta = !jarDir.exists || !currentJarMeta.contains(jarMeta)
            if (!out.exists || updateMeta) {
              withJarDirLock(jarDir) {
                if (updateMeta) {
                  val prevFiles = FileIO
                    .listAllFilesRecursively(jarDir)
                    .filter(_.filename != ".lock")
                  prevFiles.foreach(_.delete())
                  Files.write(jarMetaFile.toNIO, jarMeta.getBytes)
                }
                copyFile(path, out)
              }(retry)
            } else
              out
          case None =>
            val out =
              workspace.resolve(Directories.readonly).resolveZipPath(path.toNIO)
            copyFile(path, out)
        }
      }
    }

    def toTextDocumentIdentifier: TextDocumentIdentifier = {
      new TextDocumentIdentifier(path.toURI.toString)
    }

    def isJar: Boolean = {
      val filename = path.toNIO.getFileName.toString
      filename.endsWith(".jar") || filename.endsWith(".srcjar")
    }

    def isZip: Boolean = {
      val filename = path.toNIO.getFileName.toString
      filename.endsWith(".zip")
    }

    def canWrite: Boolean = {
      path.toNIO.toFile().canWrite()
    }

    /**
     * Reads file contents from editor buffer with fallback to disk.
     */
    def toInputFromBuffers(buffers: Buffers): m.Input.VirtualFile = {
      buffers.get(path) match {
        case Some(text) => Input.VirtualFile(path.toURI.toString(), text)
        case None => path.toInput
      }
    }

    def touch(): Unit = {
      if (!path.exists) {
        path.parent.createDirectories()
        Files.createFile(path.toNIO)
      }
    }

    def move(newPath: AbsolutePath, sco: Option[StandardCopyOption]): Path = {
      if (sco.nonEmpty)
        Files.move(path.toNIO, newPath.toNIO, sco.get)
      else Files.move(path.toNIO, newPath.toNIO)
    }

    def createDirectories(): AbsolutePath =
      AbsolutePath(Files.createDirectories(path.dealias.toNIO))

    def createAndGetDirectories(): Seq[AbsolutePath] = {
      def createDirectoriesRec(
          absolutePath: AbsolutePath,
          toCreate: Seq[AbsolutePath],
      ): Seq[AbsolutePath] = {
        if (absolutePath.exists)
          toCreate.map(path => AbsolutePath(Files.createDirectory(path.toNIO)))
        else
          createDirectoriesRec(absolutePath.parent, absolutePath +: toCreate)
      }

      createDirectoriesRec(path, Nil)
    }

    def delete(): Unit = {
      Files.delete(path.dealias.toNIO)
    }

    def deleteRecursively(): Unit = {
      path.listRecursive.toList.reverse.foreach(_.delete())
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
        StandardOpenOption.TRUNCATE_EXISTING,
      )
      try {
        Files.move(
          tmp,
          path.toNIO,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
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
        StandardOpenOption.APPEND,
      )
    }

  }

  implicit class XtensionString(value: String) {

    /**
     * Returns true if this is a Scala.js or Scala Native target
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
        lowerBound: Int,
        upperBound: Int,
    ): Int = {
      val safeLowerBound = Math.max(0, lowerBound)
      var index = upperBound
      while (index >= safeLowerBound && value(index) != char) {
        index -= 1
      }
      if (index < safeLowerBound) -1 else index
    }

    def indicesOf(str: String): List[Int] = {
      val b = new mutable.ListBuffer[Int]
      var idx = 0
      while (idx < value.length && idx >= 0) {
        idx = value.indexOf(str, idx)
        if (idx >= 0) {
          b += idx
          idx = idx + 1
        }
      }
      b.result()
    }

    def onlyIndexOf(str: String): Option[Int] =
      indicesOf(str) match {
        case Nil => Some(-1)
        case List(idx) => Some(idx)
        case _ => None
      }

    def toAbsolutePathSafe: Option[AbsolutePath] = Try(toAbsolutePath).toOption

    def toAbsolutePath: AbsolutePath = toAbsolutePath(followSymlink = true)

    def toAbsolutePath(followSymlink: Boolean): AbsolutePath =
      MtagsEnrichments.XtensionStringMtags(value).toAbsolutePath(followSymlink)

    def indexToLspPosition(index: Int): l.Position = {
      var i = 0
      var lineCount = 0
      var lineStartIdx = 0
      while (i < index && i < value.length) {
        if (value.charAt(i) == '\n') {
          lineStartIdx = i + 1
          lineCount += 1
        }
        i += 1
      }
      new l.Position(lineCount, index - lineStartIdx)
    }

    def replaceAllBetween(start: String, end: String)(
        replacement: String
    ): String =
      if (start.isEmpty || end.isEmpty)
        value
      else {
        val startIdx = value.indexOf(start)
        if (startIdx < 0)
          value
        else {
          val endIdx = value.indexOf(end, startIdx + start.length)
          if (endIdx < 0)
            value
          else {
            val b = new java.lang.StringBuilder
            b.append(value, 0, startIdx)
            b.append(replacement)
            b.append(value, endIdx + end.length, value.length)
            b.toString
          }
        }
      }

    def lineAtIndex(index: Int): Int =
      indexToLspPosition(index).getLine

  }

  implicit class XtensionTextDocumentSemanticdb(textDocument: s.TextDocument) {

    /**
     * Returns true if the symbol is defined in this document
     */
    def definesSymbol(symbol: String): Boolean = {
      textDocument.occurrences.exists { localOccurrence =>
        localOccurrence.role.isDefinition &&
        localOccurrence.symbol == symbol
      }
    }

    def toLocation(uri: URI, symbol: String): Option[l.Location] =
      toLocation(uri.toString, symbol)

    def toLocation(uri: String, symbol: String): Option[l.Location] = {
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
    def asTextEdit: Option[l.TextEdit] = {
      decodeJson(d.getData, classOf[l.TextEdit])
    }
  }

  implicit class XtensionSeverityBsp(sev: b.DiagnosticSeverity) {
    def toLsp: l.DiagnosticSeverity =
      l.DiagnosticSeverity.forValue(sev.getValue)
  }

  implicit class XtensionPositionBSp(pos: b.Position) {
    def toLsp: l.Position =
      new l.Position(pos.getLine, pos.getCharacter)
  }

  implicit class XtensionPositionRange(range: s.Range) {
    def inString(text: String): Option[String] = {
      var i = 0
      var max = 0
      def isNewline = text.charAt(i) == '\n'
      while (max < range.startLine) {
        if (isNewline) max += 1
        i += 1
      }
      val start = i + range.startCharacter
      while (max < range.endLine) {
        if (isNewline) max += 1
        i += 1
      }
      val end = i + range.endCharacter
      if (start < text.size && end <= text.size)
        Some(text.substring(start, end))
      else
        None
    }

    def toMeta(input: m.Input): Option[m.Position] =
      Try(
        m.Position.Range(
          input,
          range.startLine,
          range.startCharacter,
          range.endLine,
          range.endCharacter,
        )
      ).toOption
  }

  implicit class XtensionRangeBsp(range: b.Range) {
    def toMeta(input: m.Input): Option[m.Position] =
      Try(
        m.Position.Range(
          input,
          range.getStart.getLine,
          range.getStart.getCharacter,
          range.getEnd.getLine,
          range.getEnd.getCharacter,
        )
      ).toOption

    def toLsp: l.Range =
      new l.Range(range.getStart.toLsp, range.getEnd.toLsp)
  }

  implicit class XtensionSymbolOccurrenceProtocol(occ: s.SymbolOccurrence) {
    def toLocation(uri: String): l.Location = {
      occ.range.getOrElse(s.Range(0, 0, 0, 0)).toLocation(uri)
    }

    def encloses(
        pos: l.Position,
        includeLastCharacter: Boolean = false,
    ): Boolean =
      occ.range.isDefined &&
        occ.range.get.encloses(pos, includeLastCharacter)
  }

  implicit class XtensionDiagnosticBsp(diag: b.Diagnostic) {
    def toLsp: l.Diagnostic = {
      val ld = new l.Diagnostic(
        diag.getRange.toLsp,
        fansi.Str(diag.getMessage, ErrorMode.Strip).plainText,
        diag.getSeverity.toLsp,
        if (diag.getSource == null) "scalac" else diag.getSource,
        // We omit diag.getCode since Bloop's BSP implementation uses 'code' with different semantics
        // than LSP. See https://github.com/scalacenter/bloop/issues/1134 for details
      )
      ld.setData(diag.getData)
      ld
    }
  }

  implicit class XtensionHttpExchange(exchange: HttpServerExchange) {
    def getQuery(key: String): Option[String] =
      Option(exchange.getQueryParameters.get(key)).flatMap(_.asScala.headOption)
  }

  implicit class XtensionClasspath(classpath: List[String]) {
    def toAbsoluteClasspath: Iterator[AbsolutePath] = {
      classpath.iterator
        .map(_.toAbsolutePath)
        .filter(p => Files.exists(p.toNIO))
    }
  }

  implicit class XtensionJavacOptions(item: b.JavacOptionsItem) {
    def targetroot: AbsolutePath = {
      item.getOptions.asScala
        .find(_.startsWith("-Xplugin:semanticdb"))
        .map(arg => {
          val targetRootOpt = "-targetroot:"
          val sourceRootOpt = "-sourceroot:"
          val targetRootPos = arg.indexOf(targetRootOpt)
          val sourceRootPos = arg.indexOf(sourceRootOpt)
          if (targetRootPos > sourceRootPos)
            arg.substring(targetRootPos + targetRootOpt.size).trim()
          else
            arg
              .substring(sourceRootPos + sourceRootOpt.size, targetRootPos - 1)
              .trim()
        })
        .filter(_ != "javac-classes-directory")
        .map(AbsolutePath(_))
        .getOrElse(item.getClassDirectory.toAbsolutePath)
    }

    def isSemanticdbEnabled: Boolean = {
      item.getOptions.asScala.exists { opt =>
        opt.startsWith("-Xplugin:semanticdb")
      }
    }

    def isSourcerootDeclared: Boolean = {
      item.getOptions.asScala
        .find(_.startsWith("-Xplugin:semanticdb"))
        .map(_.contains("-sourceroot:"))
        .getOrElse(false)
    }

    def isTargetrootDeclared: Boolean = {
      item.getOptions.asScala
        .find(_.startsWith("-Xplugin:semanticdb"))
        .map(_.contains("-targetroot:"))
        .getOrElse(false)
    }

    def classpath: List[String] =
      item.getClasspath.asScala.toList

    def jarClasspath: List[AbsolutePath] =
      classpath
        .filter(_.endsWith(".jar"))
        .map(_.toAbsolutePath)

    def releaseVersion: Option[String] =
      item.getOptions.asScala
        .dropWhile(_ != "--release")
        .drop(1)
        .headOption

    def sourceVersion: Option[String] =
      item.getOptions.asScala
        .dropWhile(f => f != "--source" && f != "-source" && f != "--release")
        .drop(1)
        .headOption

    def targetVersion: Option[String] =
      item.getOptions.asScala
        .dropWhile(f => f != "--target" && f != "-target" && f != "--release")
        .drop(1)
        .headOption
  }

  implicit class XtensionScalacOptions(item: b.ScalacOptionsItem) {
    def targetroot(scalaVersion: String): AbsolutePath = {
      if (ScalaVersions.isScala3Version(scalaVersion)) {
        val options = item.getOptions.asScala
        val targetOption = if (options.contains("-semanticdb-target")) {
          val index = options.indexOf("-semanticdb-target") + 1
          if (options.size > index) Some(AbsolutePath(options(index)))
          else None
        } else None
        targetOption.getOrElse(item.getClassDirectory.toAbsolutePath)
      } else {
        semanticdbFlag("targetroot")
          .map(AbsolutePath(_))
          .getOrElse(item.getClassDirectory.toAbsolutePath)
      }
    }

    def isSemanticdbEnabled(scalaVersion: String): Boolean = {
      if (ScalaVersions.isScala3Version(scalaVersion)) {
        item.getOptions.asScala.exists { opt =>
          opt == "-Ysemanticdb" || opt == "-Xsemanticdb"
        }
      } else {
        item.getOptions.asScala.exists { opt =>
          opt.startsWith("-Xplugin:") && opt
            .contains("semanticdb-scalac")
        }
      }
    }

    def isSourcerootDeclared(scalaVersion: String): Boolean = {
      val soughtOption = if (ScalaVersions.isScala3Version(scalaVersion)) {
        "-sourceroot"
      } else {
        "-P:semanticdb:sourceroot"
      }
      item.getOptions.asScala.exists { option =>
        option.startsWith(soughtOption)
      }
    }

    def isJVM: Boolean = {
      // FIXME: https://github.com/scalacenter/bloop/issues/700
      !item.getOptions.asScala.exists(_.isNonJVMPlatformOption)
    }

    /**
     * Returns the value of a -P:semanticdb:$option:$value compiler flag.
     */
    def semanticdbFlag(name: String): Option[String] = {
      val flag = s"-P:semanticdb:$name:"
      item.getOptions.asScala
        .find(_.startsWith(flag))
        .map(_.stripPrefix(flag))
    }

    def classpath: List[String] =
      item.getClasspath.asScala.toList

    def jarClasspath: List[AbsolutePath] =
      classpath
        .filter(_.endsWith(".jar"))
        .map(_.toAbsolutePath)
  }

  implicit class XtensionChar(ch: Char) {
    def stringRepeat(n: Int): String = {
      if (n > 0)
        ch.toString * n
      else ""
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

    def foldOnlyLines: Boolean = {
      (for {
        params <- initializeParams
        capabilities <- Option(params.getCapabilities)
        textDocument <- Option(capabilities.getTextDocument)
        settings <- Option(textDocument.getFoldingRange)
        lineFoldingOnly <- Option(settings.getLineFoldingOnly)
      } yield lineFoldingOnly.booleanValue()).getOrElse(false)
    }

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

    def mapOptionInside[B](
        f: A => B
    )(implicit ec: ExecutionContext): Future[Option[B]] =
      state.map(_.map(f))

    def flatMapOptionInside[B](
        f: A => Option[B]
    )(implicit ec: ExecutionContext): Future[Option[B]] =
      state.map(_.flatMap(f))
  }

  implicit class XtensionTreeTokenStream(tree: m.Tree) {

    import scala.meta._

    def leadingTokens: Iterator[m.Token] =
      tree.origin match {
        case Origin.Parsed(input, dialect, pos) =>
          val tokens = dialect(input).tokenize.get
          tokens.slice(0, pos.start - 1).reverseIterator
        case _ => Iterator.empty
      }

    def trailingTokens: Iterator[m.Token] =
      tree.origin match {
        case Origin.Parsed(input, dialect, pos) =>
          val tokens = dialect(input).tokenize.get
          tokens.slice(pos.end, tokens.length).iterator
        case _ => Iterator.empty
      }

    def findFirstLeading(predicate: m.Token => Boolean): Option[m.Token] =
      leadingTokens.find(predicate)

    def findFirstTrailing(predicate: m.Token => Boolean): Option[m.Token] =
      trailingTokens.find(predicate)
  }

  implicit class XtensionTreeBraceHandler(stat: Tree) {

    /**
     * Check if it's possible to use braceless syntax and whether
     * it's the preferred style in the file.
     */
    def canUseBracelessSyntax(source: String): Boolean = {

      def allowBracelessSyntax(tree: Tree) = tree.origin match {
        case p: Parsed => p.dialect.allowSignificantIndentation
        case _ => false
      }

      def isNotInBraces(t: Tree): Boolean = {
        t match {
          case _: Template | _: Term.Block if t.pos.start < source.length =>
            source(t.pos.start) != '{'
          case _ => false
        }
      }

      // Let's try to use the style of any existing parent.
      @tailrec
      def existsBracelessParent(tree: Tree): Boolean = {
        tree.parent match {
          case Some(t) =>
            if (isNotInBraces(t)) true
            else existsBracelessParent(t)
          case None => existsBracelessChild(tree)
        }
      }

      // If we are at the top, let's check also the siblings
      def existsBracelessChild(tree: Tree): Boolean = {
        tree.children.exists { t =>
          if (isNotInBraces(t)) true
          else existsBracelessChild(t)
        }
      }

      allowBracelessSyntax(stat) && existsBracelessParent(stat)
    }
  }

  implicit class XtensionSourceBreakpoint(
      breakpoint: l.debug.SourceBreakpoint
  ) {

    // LSP Position is 0-based, while breakpoints are 1-based
    def toLsp = new l.Position(breakpoint.getLine() - 1, breakpoint.getColumn())
  }

}
