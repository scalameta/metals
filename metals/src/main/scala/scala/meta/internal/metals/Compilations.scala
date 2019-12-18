package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.util.Failure
import scala.util.Success
import scala.util.Try

final class Compilations(
    buildTargets: BuildTargets,
    workspace: () => AbsolutePath,
    buildServer: () => Option[BuildServerConnection],
    languageClient: MetalsLanguageClient,
    compileWorksheets: Seq[AbsolutePath] => Future[Unit]
)(implicit ec: ExecutionContext) {

  // we are maintaining a separate queue for cascade compilation since those must happen ASAP
  private val compileBatch =
    new BatchedFunction[b.BuildTargetIdentifier, b.CompileResult](compile)
  private val cascadeBatch =
    new BatchedFunction[b.BuildTargetIdentifier, b.CompileResult](compile)
  def pauseables: List[Pauseable] = List(compileBatch, cascadeBatch)

  private val isCompiling = TrieMap.empty[b.BuildTargetIdentifier, Boolean]
  private var lastCompile: collection.Set[b.BuildTargetIdentifier] = Set.empty

  def currentlyCompiling: Iterable[b.BuildTargetIdentifier] = isCompiling.keys
  def isCurrentlyCompiling(buildTarget: b.BuildTargetIdentifier): Boolean =
    isCompiling.contains(buildTarget)

  def previouslyCompiled: Iterable[b.BuildTargetIdentifier] = lastCompile
  def wasPreviouslyCompiled(buildTarget: b.BuildTargetIdentifier): Boolean =
    lastCompile.contains(buildTarget)

  def compileTargets(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[b.CompileResult] = {
    compileBatch(targets)
  }

  def compileFiles(paths: Seq[AbsolutePath]): Future[b.CompileResult] = {
    val targets = expand(paths)
    for {
      result <- compileBatch(targets)
      _ <- compileWorksheets(paths)
    } yield result
  }

  def cascadeCompileFiles(paths: Seq[AbsolutePath]): Future[b.CompileResult] = {
    val targets =
      expand(paths).flatMap(buildTargets.inverseDependencies).distinct
    for {
      result <- cascadeBatch(targets)
      _ <- compileWorksheets(paths)
    } yield result
  }

  def cancel(): Unit = {
    compileBatch.cancelCurrentRequest()
    cascadeBatch.cancelCurrentRequest()
  }

  def expand(paths: Seq[AbsolutePath]): Seq[b.BuildTargetIdentifier] = {
    def isCompilable(path: AbsolutePath): Boolean =
      path.isScalaOrJava && !path.isDependencySource(workspace())

    val compilablePaths = paths.filter(isCompilable)
    val targets = compilablePaths.flatMap(buildTargets.inverseSources).distinct

    if (targets.isEmpty && compilablePaths.nonEmpty) {
      scribe.warn(s"no build target for: ${compilablePaths.mkString("\n  ")}")
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

    val result = compilation.asScala.andThen {
      case result => updateCompiledTargetState(result)
    }

    CancelableFuture(result, Cancelable(() => compilation.cancel(false)))
  }

  private def updateCompiledTargetState(result: Try[b.CompileResult]): Unit =
    result match {
      case Failure(_) =>
        isCompiling.clear()
      case Success(_) =>
        lastCompile = isCompiling.keySet
        isCompiling.clear()
    }
}
