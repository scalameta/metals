package scala.meta.internal.metals.mbt

import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.{util => ju}
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager

import scala.collection.concurrent.TrieMap
import scala.collection.parallel.mutable.ParArray
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.Sleeper
import scala.meta.pc.ProgressBars
import scala.meta.{pc => pc}

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.turbine.binder.Binder
import com.google.turbine.binder.ClassPathBinder
import com.google.turbine.binder.JimageClassBinder
import com.google.turbine.binder.Processing
import com.google.turbine.diag.SourceFile
import com.google.turbine.diag.TurbineLog
import com.google.turbine.lower.Lower
import com.google.turbine.parse.Parser
import com.google.turbine.tree.Tree

object TurbineCompiler {

  val emptyResult: TurbineCompileResult = TurbineCompileResult(
    ClassPathBinder.bindClasspath(List.empty.asJava),
    Lower.Lowered.create(ImmutableMap.of(), ImmutableSet.of()),
  )

  def compileClassfiles[T](
      toParse: ParArray[T],
      toSourceFile: T => Option[SourceFile],
      classpath: Seq[Path],
      progressBars: ProgressBars,
  )(implicit rc: ReportContext): TurbineCompileResult = {
    val bar =
      progressBars.start(new ProgressBars.StartProgressBarParams("Outlining"))
    try {
      val units = parseInputs(toParse, toSourceFile)
      compileClassfilesInternal(units, classpath)
    } catch {
      case NonFatal(e) =>
        PcQueryContext(None, () => classpath.mkString("\n"))
          .report("turbine-file-manager-error", e, "")
        emptyResult
    } finally {
      progressBars.end(bar)
    }
  }

  private def parseInputs[T](
      inputs: ParArray[T],
      toSourceFile: T => Option[SourceFile],
  ): ImmutableList[Tree.CompUnit] = {
    val result = new ConcurrentLinkedQueue[Tree.CompUnit]()
    inputs.foreach { input =>
      try {
        toSourceFile(input) match {
          case None =>
          // Silently ignore entries that may not exist
          case Some(source) =>
            result.add(Parser.parse(source))
        }
      } catch {
        case NonFatal(_) =>
        // Silently ignore parse errors, they're very noisy
      }
    }
    ImmutableList.copyOf(result)
  }

  private def compileClassfilesInternal(
      units: ImmutableList[Tree.CompUnit],
      classpath: Seq[Path],
  ): TurbineCompileResult = {
    val log = new TurbineLog()
    val boundClasspath =
      ClassPathBinder.bindClasspath(validClasspaths(classpath).asJava)
    val result = Binder.bind(
      log,
      units,
      boundClasspath,
      Processing.ProcessorInfo.empty(),
      JimageClassBinder.bindDefault(),
      Optional.empty(),
    )
    val lowered = Lower.lowerAll(
      Lower.LowerOptions.createDefault(),
      result.units(),
      result.modules(),
      result.classPathEnv(),
    )
    TurbineCompileResult(boundClasspath, lowered)
  }
  private def validClasspaths(classpath: Seq[Path]): Seq[Path] = {
    classpath.filter(path =>
      Files.exists(path) && path.getFileName().toString().endsWith(".jar")
    )
  }
}

private case class SourcepathJavaFileObject(
    javaFileObject: JavaFileObject,
    isCompiled: AtomicBoolean = new AtomicBoolean(false),
    isDeleted: AtomicBoolean = new AtomicBoolean(false),
)

class TurbineCompiler[T](
    allCompilationUnits: () => ParArray[T],
    parseUnit: T => Option[SourceFile],
    classpath: () => Seq[Path],
    progressBars: ProgressBars,
    debounceDelay: FiniteDuration,
    sleeper: Sleeper,
    onIndexingDone: () => Unit,
)(implicit ec: ExecutionContext, rc: ReportContext) {
  private val sourcepathByPackageName =
    TrieMap.empty[String, ju.concurrent.ConcurrentLinkedDeque[
      SourcepathJavaFileObject
    ]]
  // Binary names of classes that have been deleted but not yet recompiled.
  // These are excluded from CLASS_PATH listing until the next turbine compile
  // removes them from the compiled output.
  private val deletedBinaryNames = ju.Collections.newSetFromMap(
    new ju.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
  )
  private def sourcepathSources(): Seq[SourcepathJavaFileObject] = {
    for {
      (_, deque) <- sourcepathByPackageName.iterator
      sourcepathJavaFileObject <- deque.asScala.iterator
    } yield sourcepathJavaFileObject
  }.toSeq
  // When delay is >= 1 hour, consider turbine recompilation effectively disabled.
  // In this mode, we rely entirely on SOURCE_PATH fallback for updated sources.
  private val isRecompilationDisabled: Boolean =
    debounceDelay.toMillis >= 3600000

  private val doCompile =
    BatchedFunction.fromFuture[Unit, TurbineCompileResult](
      _ => {
        // If recompilation is disabled, return immediately without waiting.
        // This allows SOURCE_PATH fallback to handle all updates.
        if (isRecompilationDisabled) {
          Future.successful(result)
        } else {
          val toCompile = sourcepathSources()
          for {
            _ <- sleeper.sleep(debounceDelay)
          } yield {
            val result = doCompileNow()
            toCompile.foreach(_.isCompiled.set(true))
            result
          }
        }
      },
      "turbine-compile",
    )
  private def cleanup(): Unit = {
    sourcepathByPackageName.valuesIterator.foreach(
      _.removeIf(_.isCompiled.get())
    )
  }

  private var result = TurbineCompiler.emptyResult
  def doCompileNow(): TurbineCompileResult = {
    result = TurbineCompiler.compileClassfiles(
      allCompilationUnits(),
      parseUnit,
      classpath(),
      progressBars,
    )
    cleanup()
    // Clear deleted binary names after recompile - they are no longer in the compiled output
    deletedBinaryNames.clear()
    onIndexingDone()
    result
  }

  /**
   * Called when a file is deleted. Tracks the binary names of the deleted classes
   * so they can be excluded from CLASS_PATH listing until the next turbine recompile.
   * Also soft-deletes the file from the sourcepath so it's not returned via SOURCE_PATH.
   *
   * @param binaryNames The binary names of classes defined in the deleted file
   * @param fileUri The URI of the deleted file (used to soft-delete from sourcepath)
   */
  def onDidDelete(binaryNames: Seq[String], fileUri: String): Unit = {
    binaryNames.foreach(deletedBinaryNames.add)
    // Soft-delete from sourcepath so the deleted file isn't returned via SOURCE_PATH
    sourcepathByPackageName.valuesIterator.foreach { deque =>
      deque.asScala.foreach { obj =>
        if (obj.javaFileObject.toUri().toString() == fileUri) {
          obj.isDeleted.set(true)
        }
      }
    }
  }

  /**
   * Check if a binary name has been deleted but not yet recompiled.
   */
  def isDeleted(binaryName: String): Boolean = {
    deletedBinaryNames.contains(binaryName)
  }

  /**
   * Check if a binary name has a pending source on the sourcepath that hasn't
   * been compiled yet. Used to exclude stale classes from CLASS_PATH so javac
   * falls back to SOURCE_PATH for the updated source.
   */
  def hasPendingSource(binaryName: String): Boolean = {
    // binaryName is like "a/Helper", we need to check if there's a pending
    // source file that defines this class
    val packageName = {
      val lastSlash = binaryName.lastIndexOf('/')
      if (lastSlash >= 0) binaryName.substring(0, lastSlash).replace('/', '.')
      else ""
    }
    sourcepathByPackageName.get(packageName) match {
      case None => false
      case Some(deque) =>
        deque.asScala.exists { obj =>
          // Only check sources that haven't been compiled or deleted yet
          !obj.isCompiled.get() && !obj.isDeleted.get() && {
            // Check if this source file defines the class we're looking for
            obj.javaFileObject match {
              case s: pc.SemanticdbCompilationUnit =>
                // The binaryName from SemanticdbCompilationUnit uses dots, convert to slashes
                s.binaryName().replace('.', '/') == binaryName ||
                s.toplevelSymbols().asScala.exists { sym =>
                  sym.stripSuffix("#").stripSuffix(".") == binaryName
                }
              case _ => false
            }
          }
        }
    }
  }

  def compileNow(): Future[TurbineCompileResult] = Future {
    doCompileNow()
  }

  def onDidChange(
      packageName: String,
      javaFileObject: JavaFileObject,
  ): Future[TurbineCompileResult] = {
    require(
      !packageName.endsWith("/"),
      s"package name '$packageName' cannot end with '/'. It should be a javac dot-separate package name like 'com.foo'",
    )
    val deque = sourcepathByPackageName.getOrElseUpdate(
      packageName,
      new ju.concurrent.ConcurrentLinkedDeque[SourcepathJavaFileObject](),
    )
    val obj = SourcepathJavaFileObject(javaFileObject)
    deque.addFirst(obj)

    // Clean up duplicate entries in the dequeue for this file.
    deque.removeIf(item =>
      item.ne(obj) &&
        item.javaFileObject.getName() == obj.javaFileObject.getName()
    )

    doCompile(())
  }

  def createFileManager(
      underlying: StandardJavaFileManager
  ): JavaFileManager = {
    new TurbineClasspathFileManager(
      underlying,
      () => result,
      listSourcepath,
      isDeleted,
      hasPendingSource,
    )
  }

  private def listSourcepath(
      packageName: String
  ): java.lang.Iterable[JavaFileObject] = {
    sourcepathByPackageName.get(packageName) match {
      case None =>
        ju.Collections.emptyList()
      case Some(deque) =>
        val isHandledFileName = new ju.HashSet[String]()
        deque.asScala.view
          .flatMap(obj =>
            if (
              // compiled files are loaded via CLASS_PATH
              obj.isCompiled.get() ||
              // soft-deleted files should not be returned
              obj.isDeleted.get() ||
              // if a file is changed multiple times within the same window then
              // we will have multiple entries in the dequeue.
              isHandledFileName.contains(obj.javaFileObject.getName())
            ) {
              None
            } else {
              isHandledFileName.add(obj.javaFileObject.getName())
              Some(obj.javaFileObject)
            }
          )
          .asJava
    }
  }
}
