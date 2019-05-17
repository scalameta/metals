package scala.meta.internal.metals
import ch.epfl.scala.{bsp4j => b}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

final class Compilations(
    buildTargets: BuildTargets,
    classes: BuildTargetClasses,
    workspace: () => AbsolutePath,
    buildServer: () => Option[BuildServerConnection]
)(implicit ec: ExecutionContext) {

  // we are maintaining a separate queue for cascade compilation since those must happen ASAP
  private val compilationBatch = new CompilationBatch
  private val cascadeBatch = new CascadeCompilationBatch

  private val isCompiling = TrieMap.empty[b.BuildTargetIdentifier, Boolean]
  private var lastCompile: collection.Set[b.BuildTargetIdentifier] = Set.empty

  def currentlyCompiling: Iterable[b.BuildTargetIdentifier] = isCompiling.keys
  def currentlyCompiling(buildTarget: b.BuildTargetIdentifier): Boolean =
    isCompiling.contains(buildTarget)

  def previouslyCompiled: Iterable[b.BuildTargetIdentifier] = lastCompile
  def previouslyCompiled(buildTarget: b.BuildTargetIdentifier): Boolean =
    lastCompile.contains(buildTarget)

  def compileFiles(paths: Seq[AbsolutePath]): Future[b.CompileResult] =
    compilationBatch.enqueue(paths)

  def cascadeCompileFiles(paths: Seq[AbsolutePath]): Future[b.CompileResult] =
    cascadeBatch.enqueue(paths)

  def compile(target: b.BuildTargetIdentifier): Future[b.CompileResult] =
    compilationBatch.enqueue(target)

  def cancel(): Unit = {
    compilationBatch.cancel()
    cascadeBatch.cancel()
  }

  private abstract class Batch extends Cancelable {
    private val batch =
      new BatchedFunction[b.BuildTargetIdentifier, b.CompileResult](compile)

    def enqueue(target: b.BuildTargetIdentifier): Future[b.CompileResult] =
      batch(target)

    def enqueue(paths: Seq[AbsolutePath]): Future[b.CompileResult] = {
      def isCompilable(path: AbsolutePath): Boolean =
        path.isScalaOrJava && !path.isDependencySource(workspace())

      val targets =
        paths.filter(isCompilable).flatMap(buildTargets.inverseSources).distinct

      if (targets.isEmpty) {
        scribe.warn(s"no build target: ${paths.mkString("\n  ")}")
      }

      batch(targets)
    }

    private def compile(
        requestTargets: Seq[b.BuildTargetIdentifier]
    ): CancelableFuture[b.CompileResult] = {
      val result = for {
        connection <- buildServer()
        targets = resolve(requestTargets)
        if targets.nonEmpty
      } yield compile(connection, targets)

      result.getOrElse {
        val result = new b.CompileResult(b.StatusCode.CANCELLED)
        Future.successful(result).asCancelable
      }
    }

    private def compile(
        connection: BuildServerConnection,
        targets: Seq[b.BuildTargetIdentifier]
    ): CancelableFuture[b.CompileResult] = {
      val params = new b.CompileParams(targets.asJava)
      targets.foreach(target => isCompiling(target) = true)
      val compilation = connection.compile(params)
      val task = for {
        result <- compilation.asScala
        _ <- {
          lastCompile = isCompiling.keySet
          isCompiling.clear()
          if (result.isOK) classes.onCompiled(targets)
          else Future.successful(())
        }
      } yield result

      CancelableFuture(
        task,
        Cancelable(() => compilation.cancel(false))
      )
    }

    protected def resolve(
        targets: Seq[b.BuildTargetIdentifier]
    ): Seq[b.BuildTargetIdentifier]

    override def cancel(): Unit = {
      batch.cancelCurrentRequest()
    }
  }

  private final class CompilationBatch extends Batch {
    protected def resolve(
        targets: Seq[b.BuildTargetIdentifier]
    ): Seq[b.BuildTargetIdentifier] =
      targets
  }

  private final class CascadeCompilationBatch extends Batch {
    protected def resolve(
        targets: Seq[b.BuildTargetIdentifier]
    ): Seq[b.BuildTargetIdentifier] =
      targets.flatMap(buildTargets.inverseDependencies).distinct

  }
}
