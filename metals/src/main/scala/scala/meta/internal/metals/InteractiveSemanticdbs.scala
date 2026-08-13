package scala.meta.internal.metals

import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.scalacli.ScalaCliServers
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Shebang
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import com.google.common.collect.MapMaker

/**
 * Produces SemanticDBs on-demand by using the presentation compiler.
 *
 * Only used to provide navigation inside external library sources, not used to compile
 * workspace sources.
 *
 * Uses persistent storage to keep track of what external source file is associated
 * with what build target (to determine classpath and compiler options).
 */
final class InteractiveSemanticdbs(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    charset: Charset,
    tables: Tables,
    compilers: () => Compilers,
    semanticdbIndexer: () => SemanticdbIndexer,
    javaInteractiveSemanticdb: JavaInteractiveSemanticdb,
    buffers: Buffers,
    scalaCliServers: => ScalaCliServers,
) extends Cancelable
    with Semanticdbs {

  private val textDocumentCache =
    new InteractiveSemanticdbCache[AbsolutePath, s.TextDocument]()
  // Weak keys avoid extending the lifetime of evicted presentation compilers.
  private val compilerLanes =
    new MapMaker().weakKeys().makeMap[AnyRef, Object]()

  def reset(): Unit = {
    textDocumentCache.clear()
  }

  override def cancel(): Unit = {
    reset()
  }

  override def textDocument(
      source: AbsolutePath
  ): TextDocumentLookup = textDocument(source, unsavedContents = None)

  def onClose(path: AbsolutePath): Unit = {
    textDocumentCache.remove(path)
  }

  def textDocument(
      source: AbsolutePath,
      unsavedContents: Option[String],
  ): TextDocumentLookup = {
    def doesNotBelongToBuildTarget = buildTargets.inverseSources(source).isEmpty
    lazy val sourceText =
      buffers.get(source).orElse {
        if (source.exists) Some(source.readText(charset))
        else None
      }
    def shouldTryCalculateInteractiveSemanticdb = {
      source.isLocalFileSystem(workspace) && (
        unsavedContents.isDefined ||
          source.isInReadonlyDirectory(workspace) || // dependencies
          source.isSbt || // sbt files
          source.isMill || // mill files
          source.isWorksheet || // worksheets
          doesNotBelongToBuildTarget || // standalone files
          scalaCliServers.loadedExactly(source) || // scala-cli single files
          sourceText.exists(
            _.startsWith(Shebang.shebang)
          ) // starts with shebang
      ) || source.isJarFileSystem // dependencies
    }

    // anything aside from `*.scala`, `*.sbt`, `*.mill`, `*.sc`, `*.java` file
    def isExcludedFile = !source.isScalaFilename && !source.isJavaFilename

    if (isExcludedFile || !shouldTryCalculateInteractiveSemanticdb) {
      TextDocumentLookup.NotFound(source)
    } else {
      val result = unsavedContents.orElse(sourceText) match {
        case None => null
        case Some(text) =>
          val adjustedText =
            if (text.startsWith(Shebang.shebang))
              "//" + text.drop(2)
            else text
          val sha = MD5.compute(adjustedText)
          textDocumentCache.compute(
            source,
            () => Try(compilationFor(source, adjustedText)),
            _.md5 == sha,
          ) { (path, compilation) =>
            compilation.run() match {
              case Success(doc) if doc != null =>
                if (!source.isDependencySource(workspace))
                  semanticdbIndexer().onChange(source, doc)
                doc
              case _ => null
            }
          }
      }
      TextDocumentLookup.fromOption(source, Option(result))
    }
  }

  /**
   * Persist relationship between this dependency source and its enclosing build target
   */
  def didDefinition(source: AbsolutePath, result: DefinitionResult): Unit = {
    for {
      destination <- result.definition
      if destination.isDependencySource(workspace)
      buildTarget = buildTargets.inverseSources(source)
    } {
      if (source.isWorksheet) {
        tables.worksheetSources.setWorksheet(destination, source)
      } else {
        buildTarget.foreach { target =>
          tables.dependencySources.setBuildTarget(destination, target)
        }
      }
    }
  }

  private def compilationFor(
      source: AbsolutePath,
      text: String,
  ): InteractiveSemanticdbCompilation =
    if (source.isJavaFilename) {
      val buildTarget = buildTargets.inferBuildTarget(source)
      new InteractiveSemanticdbCompilation(
        compilationLane(javaInteractiveSemanticdb),
        InteractiveSemanticdbCompilationContext.Java(
          buildTarget.map(_.getUri())
        ),
        () => javaInteractiveSemanticdb.textDocument(source, text, buildTarget),
      )
    } else {
      val selectedCompilers = compilers()
      val compiler = selectedCompilers.semanticdbCompiler(source)
      val lane = compilationLane(compiler)
      new InteractiveSemanticdbCompilation(
        lane,
        InteractiveSemanticdbCompilationContext.Scala(lane),
        () => selectedCompilers.semanticdbTextDocument(source, text, compiler),
      )
    }

  private def compilationLane(
      compiler: AnyRef
  ): InteractiveSemanticdbCompilationLane = {
    val identity = compilerLanes.computeIfAbsent(compiler, _ => new Object())
    InteractiveSemanticdbCompilationLane(identity)
  }

}

private[metals] final class InteractiveSemanticdbCompilation(
    val lane: InteractiveSemanticdbCompilationLane,
    val context: InteractiveSemanticdbCompilationContext,
    compile: () => s.TextDocument,
) {
  def run(): Try[s.TextDocument] = Try(compile())
}

private[metals] final class InteractiveSemanticdbCache[K, V <: AnyRef] {
  private val values =
    new ConcurrentHashMap[K, InteractiveSemanticdbCacheEntry[V]]()
  private val lifecycleLock = new ReentrantReadWriteLock()

  def clear(): Unit = {
    val lock = lifecycleLock.writeLock()
    lock.lock()
    try {
      values.clear()
    } finally lock.unlock()
  }

  def remove(key: K): Unit = {
    val lock = lifecycleLock.readLock()
    lock.lock()
    try values.remove(key)
    finally lock.unlock()
  }

  def compute(
      key: K,
      prepare: () => Try[InteractiveSemanticdbCompilation],
      isCurrent: V => Boolean,
  )(compile: (K, InteractiveSemanticdbCompilation) => V): V = {
    val lock = lifecycleLock.readLock()
    lock.lock()
    try {
      prepare() match {
        case Success(compilation) =>
          val entry = values.compute(
            key,
            (path, existing) =>
              if (
                existing != null &&
                existing.context == compilation.context &&
                isCurrent(existing.value)
              ) existing
              else {
                val value = compilation.lane.serialized(
                  compile(path, compilation)
                )
                if (value == null) null
                else
                  new InteractiveSemanticdbCacheEntry(
                    compilation.context,
                    value,
                  )
              },
          )
          if (entry == null) null.asInstanceOf[V]
          else entry.value
        case _ => null.asInstanceOf[V]
      }
    } finally lock.unlock()
  }
}

private final class InteractiveSemanticdbCacheEntry[V](
    val context: InteractiveSemanticdbCompilationContext,
    val value: V,
)

private[metals] sealed trait InteractiveSemanticdbCompilationContext

private[metals] object InteractiveSemanticdbCompilationContext {
  final case class Scala(lane: InteractiveSemanticdbCompilationLane)
      extends InteractiveSemanticdbCompilationContext
  final case class Java(buildTargetUri: Option[String])
      extends InteractiveSemanticdbCompilationContext
}

private[metals] final class InteractiveSemanticdbCompilationLane private (
    private val compiler: AnyRef
) {
  override def equals(other: Any): Boolean = other match {
    case that: InteractiveSemanticdbCompilationLane =>
      compiler eq that.compiler
    case _ => false
  }

  override def hashCode(): Int = System.identityHashCode(compiler)

  def serialized[A](action: => A): A = compiler.synchronized(action)
}

private[metals] object InteractiveSemanticdbCompilationLane {
  def apply(compiler: AnyRef): InteractiveSemanticdbCompilationLane =
    new InteractiveSemanticdbCompilationLane(compiler)
}
