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
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

final class Compilations(
    buildTargets: BuildTargets,
    classes: BuildTargetClasses,
    workspace: () => AbsolutePath,
    buildServer: BuildTargetIdentifier => Option[BuildServerConnection],
    languageClient: MetalsLanguageClient,
    isCurrentlyFocused: b.BuildTargetIdentifier => Boolean,
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

  def compileTarget(
      target: b.BuildTargetIdentifier
  ): Future[b.CompileResult] = {
    compileBatch(Seq(target))
  }

  def compileFile(path: AbsolutePath): Future[b.CompileResult] = {
    val targetOpt = expand(path)
    for {
      result <- compileBatch(targetOpt.toSeq)
      _ <- compileWorksheets(Seq(path))
    } yield result
  }

  def compileFiles(paths: Seq[AbsolutePath]): Future[Unit] = {
    val targets = expand(paths)
    for {
      result <- compileBatch(targets)
      _ <- compileWorksheets(paths)
    } yield ()
  }

  def cascadeCompileFiles(paths: Seq[AbsolutePath]): Future[b.CompileResult] = {
    val pathsTargets = expand(paths)
    val targets =
      (pathsTargets ++ pathsTargets.flatMap(buildTargets.inverseDependencies)).distinct
    for {
      result <- cascadeBatch(targets)
      _ <- compileWorksheets(paths)
    } yield result
  }

  def cancel(): Unit = {
    compileBatch.cancelCurrentRequest()
    cascadeBatch.cancelCurrentRequest()
  }

  def expand(path: AbsolutePath): Option[b.BuildTargetIdentifier] = {
    def isCompilable: Boolean =
      path.isScalaOrJava && !path.isDependencySource(workspace())

    val targetOpt =
      if (isCompilable) buildTargets.inverseSources(path)
      else None

    if (targetOpt.isEmpty && isCompilable)
      scribe.warn(s"no build target for: $path")

    targetOpt
  }

  private def expand(paths: Seq[AbsolutePath]): Seq[b.BuildTargetIdentifier] =
    paths.flatMap(path => expand(path).toSeq)

  private def compile(
      targets: Seq[b.BuildTargetIdentifier]
  ): CancelableFuture[b.CompileResult] = {

    val targetsByBuildServer = targets
      .flatMap(t => buildServer(t).map(_ -> t).toSeq)
      .groupBy(_._1)
      .map {
        case (k, l) =>
          (k, l.map(_._2))
      }

    targetsByBuildServer.toList match {
      case Nil =>
        val result = new b.CompileResult(b.StatusCode.CANCELLED)
        Future.successful(result).asCancelable
      case (bs, bsTargets) :: Nil =>
        compile(bs, bsTargets)
      case l =>
        scribe.warn(
          "Cannot compile files from different build tools at the same time"
        )
        ???
    }
  }

  private def compile(
      connection: BuildServerConnection,
      targets: Seq[b.BuildTargetIdentifier]
  ): CancelableFuture[b.CompileResult] = {
    val params = new b.CompileParams(targets.asJava)
    targets.foreach(target => isCompiling(target) = true)
    val compilation = connection.compile(params)

    val result = compilation.asScala
      .andThen {
        case result =>
          updateCompiledTargetState(result)

          // See https://github.com/scalacenter/bloop/issues/1067
          classes.rebuildIndex(targets).foreach { _ =>
            if (targets.exists(isCurrentlyFocused)) {
              languageClient.refreshModel()
            }
          }
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
