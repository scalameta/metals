package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}

final class Compilations(
    buildTargets: BuildTargets,
    classes: BuildTargetClasses,
    workspace: () => AbsolutePath,
    languageClient: MetalsLanguageClient,
    isCurrentlyFocused: b.BuildTargetIdentifier => Boolean,
    compileWorksheets: Seq[AbsolutePath] => Future[Unit]
)(implicit ec: ExecutionContext) {

  // we are maintaining a separate queue for cascade compilation since those must happen ASAP
  private val compileBatch =
    new BatchedFunction[
      b.BuildTargetIdentifier,
      Map[BuildTargetIdentifier, b.CompileResult]
    ](compile)
  private val cascadeBatch =
    new BatchedFunction[
      b.BuildTargetIdentifier,
      Map[BuildTargetIdentifier, b.CompileResult]
    ](compile)
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
    compileBatch(target).map { results =>
      results.getOrElse(target, new b.CompileResult(b.StatusCode.CANCELLED))
    }
  }

  def compileTargets(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    compileBatch(targets).ignoreValue
  }

  def compileFile(path: AbsolutePath): Future[b.CompileResult] = {
    def empty = new b.CompileResult(b.StatusCode.CANCELLED)
    for {
      result <- {
        expand(path) match {
          case None => Future.successful(empty)
          case Some(target) =>
            compileBatch(target)
              .map(res => res.getOrElse(target, empty))
        }
      }
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

  def cascadeCompileFiles(paths: Seq[AbsolutePath]): Future[Unit] = {
    val targets =
      expand(paths).flatMap(buildTargets.inverseDependencyLeaves).distinct
    for {
      _ <- cascadeBatch(targets)
      _ <- compileWorksheets(paths)
    } yield ()
  }

  def cancel(): Unit = {
    compileBatch.cancelCurrentRequest()
    cascadeBatch.cancelCurrentRequest()
  }

  def recompileAll(): Future[Unit] = {
    cancel()

    def clean(
        connectionOpt: Option[BuildServerConnection],
        targetIds: Seq[BuildTargetIdentifier]
    ): Future[Unit] = {
      val cleaned = connectionOpt match {
        case None =>
          Future.failed(
            new Exception(
              s"No build server for target IDs ${targetIds.map(_.getUri).mkString(", ")}"
            )
          )
        case Some(connection) =>
          val params = new b.CleanCacheParams(targetIds.asJava)
          connection.clean(params).asScala
      }

      for {
        cleanResult <- cleaned
        if cleanResult.getCleaned() == true
        compiled <- compile(targetIds).future
      } yield ()
    }

    val groupedTargetIds = buildTargets.allBuildTargetIds
      .groupBy(buildTargets.buildServerOf(_))
    Future
      .traverse(groupedTargetIds) {
        case (connectionOpt, targetIds) =>
          clean(connectionOpt, targetIds)
      }
      .ignoreValue
  }

  private def expand(path: AbsolutePath): Option[b.BuildTargetIdentifier] = {
    val isCompilable =
      path.isScalaOrJava && !path.isDependencySource(workspace())

    if (isCompilable) {
      val targetOpt = buildTargets.inverseSources(path)

      if (targetOpt.isEmpty) {
        scribe.warn(s"no build target for: $path")
      }

      targetOpt
    } else
      None
  }

  def expand(paths: Seq[AbsolutePath]): Seq[b.BuildTargetIdentifier] =
    paths.flatMap(expand(_)).distinct

  private def compile(
      targets: Seq[b.BuildTargetIdentifier]
  ): CancelableFuture[Map[BuildTargetIdentifier, b.CompileResult]] = {

    val targetsByBuildServer = targets
      .flatMap(target =>
        buildTargets.buildServerOf(target).map(_ -> target).toSeq
      )
      .groupBy {
        case (buildServer, _) =>
          buildServer
      }
      .map {
        case (buildServer, targets) =>
          val targets0 = targets.map {
            case (_, target) => target
          }
          (buildServer, targets0)
      }

    targetsByBuildServer.toList match {
      case Nil =>
        Future
          .successful(Map.empty[BuildTargetIdentifier, b.CompileResult])
          .asCancelable
      case (buildServer, targets) :: Nil =>
        compile(buildServer, targets)
          .map(res => targets.map(target => target -> res).toMap)
      case targetList =>
        val futures = targetList.map {
          case (buildServer, targets) =>
            compile(buildServer, targets).map(res =>
              targets.map(target => target -> res)
            )
        }
        CancelableFuture.sequence(futures).map(_.flatten.toMap)
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
