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
  private val compileBatch =
    new BatchedFunction[b.BuildTargetIdentifier, b.CompileResult](compile)
  private val cascadeBatch =
    new BatchedFunction[b.BuildTargetIdentifier, b.CompileResult](compile)

  private val isCompiling = TrieMap.empty[b.BuildTargetIdentifier, Boolean]
  private var lastCompile: collection.Set[b.BuildTargetIdentifier] = Set.empty

  def pause(): Unit = {
    compileBatch.pause()
    cascadeBatch.pause()
  }

  def unpause(): Unit = {
    compileBatch.unpause()
    cascadeBatch.unpause()
  }

  def currentlyCompiling: Iterable[b.BuildTargetIdentifier] = isCompiling.keys
  def isCurrentlyCompiling(buildTarget: b.BuildTargetIdentifier): Boolean =
    isCompiling.contains(buildTarget)

  def previouslyCompiled: Iterable[b.BuildTargetIdentifier] = lastCompile
  def wasPreviouslyCompiled(buildTarget: b.BuildTargetIdentifier): Boolean =
    lastCompile.contains(buildTarget)

  def compileFiles(paths: Seq[AbsolutePath]): Future[b.CompileResult] = {
    val targets = expand(paths)
    compileBatch(targets)
  }

  def cascadeCompileFiles(paths: Seq[AbsolutePath]): Future[b.CompileResult] = {
    val targets =
      expand(paths).flatMap(buildTargets.inverseDependencies).distinct
    cascadeBatch(targets)
  }

  def cancel(): Unit = {
    compileBatch.cancelCurrentRequest()
    cascadeBatch.cancelCurrentRequest()
  }

  def expand(paths: Seq[AbsolutePath]): Seq[b.BuildTargetIdentifier] = {
    def isCompilable(path: AbsolutePath): Boolean =
      path.isScalaOrJava && !path.isDependencySource(workspace())

    val targets =
      paths.filter(isCompilable).flatMap(buildTargets.inverseSources).distinct

    if (targets.isEmpty) {
      scribe.warn(s"no build target: ${paths.mkString("\n  ")}")
    }

    targets
  }

  private def compile(
      targets: Seq[b.BuildTargetIdentifier]
  ): CancelableFuture[b.CompileResult] = {
    val result = for {
      connection <- buildServer()
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
}
