package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}

final class Compilations(
    buildTargets: BuildTargets,
    classes: BuildTargetClasses,
    workspace: () => AbsolutePath,
    languageClient: MetalsLanguageClient,
    refreshTestSuites: () => Unit,
    afterCompilation: () => Unit,
    isCurrentlyFocused: b.BuildTargetIdentifier => Boolean,
    compileWorksheets: Seq[AbsolutePath] => Future[Unit],
    onStartCompilation: () => Unit,
)(implicit ec: ExecutionContext) {

  // we are maintaining a separate queue for cascade compilation since those must happen ASAP
  private val compileBatch =
    new BatchedFunction[
      b.BuildTargetIdentifier,
      Map[BuildTargetIdentifier, b.CompileResult],
    ](compile)
  private val cascadeBatch =
    new BatchedFunction[
      b.BuildTargetIdentifier,
      Map[BuildTargetIdentifier, b.CompileResult],
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

  def compilationFinished(targets: Seq[BuildTargetIdentifier]): Future[Unit] =
    if (currentlyCompiling.isEmpty) {
      Future(())
    } else {
      cascadeCompile(targets)
    }

  def compilationFinished(
      source: AbsolutePath
  ): Future[Unit] =
    expand(source).flatMap(targets => compilationFinished(targets.toSeq))

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
      targetOpt <- expand(path)
      result <- targetOpt match {
        case None => Future.successful(empty)
        case Some(target) =>
          compileBatch(target)
            .map(res => res.getOrElse(target, empty))
      }
      _ <- compileWorksheets(Seq(path))
    } yield result
  }

  def compileFiles(paths: Seq[AbsolutePath]): Future[Unit] = {
    for {
      targets <- expand(paths)
      _ <- compileBatch(targets)
      _ <- compileWorksheets(paths)
    } yield ()
  }

  def cascadeCompile(targets: Seq[BuildTargetIdentifier]): Future[Unit] = {
    val inverseDependencyLeaves =
      targets.flatMap(buildTargets.inverseDependencyLeaves).distinct
    cascadeBatch(inverseDependencyLeaves).map(_ => ())
  }

  def cascadeCompileFiles(paths: Seq[AbsolutePath]): Future[Unit] =
    for {
      targets <- expand(paths)
      _ <- cascadeCompile(targets)
      _ <- compileWorksheets(paths)
    } yield ()

  def cancel(): Unit = {
    cascadeBatch.cancelAll()
    compileBatch.cancelAll()
    buildTargets.all
      .flatMap { target =>
        buildTargets.buildServerOf(target.getId())
      }
      .distinct
      .foreach { conn =>
        conn.cancelCompilations()
      }

  }

  def recompileAll(): Future[Unit] = {
    cancel()

    def clean(
        connectionOpt: Option[BuildServerConnection],
        targetIds: Seq[BuildTargetIdentifier],
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
      .toSeq
    Future
      .traverse(groupedTargetIds) { case (connectionOpt, targetIds) =>
        clean(connectionOpt, targetIds)
      }
      .ignoreValue
  }

  private def expand(
      path: AbsolutePath
  ): Future[Option[b.BuildTargetIdentifier]] = {
    val isCompilable =
      (path.isScalaOrJava || path.isSbt) &&
        !path.isDependencySource(workspace())

    if (isCompilable) {
      val targetOpt = buildTargets.inverseSourcesBsp(path)
      targetOpt.foreach {
        case tgts if tgts.isEmpty => scribe.warn(s"no build target for: $path")
        case _ =>
      }

      targetOpt
    } else
      Future.successful(None)
  }

  def expand(paths: Seq[AbsolutePath]): Future[Seq[b.BuildTargetIdentifier]] = {
    val expansions = paths.map(expand)
    Future.sequence(expansions).map(_.flatten)
  }

  private def compile(
      targets: Seq[b.BuildTargetIdentifier]
  ): CancelableFuture[Map[BuildTargetIdentifier, b.CompileResult]] = {

    val targetsByBuildServer = targets
      .flatMap(target =>
        buildTargets.buildServerOf(target).map(_ -> target).toSeq
      )
      .groupBy { case (buildServer, _) =>
        buildServer
      }
      .map { case (buildServer, targets) =>
        val targets0 = targets.collect {
          case (_, target) if buildTargets.canCompile(target) =>
            target
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
        val futures = targetList.map { case (buildServer, targets) =>
          compile(buildServer, targets).map(res =>
            targets.map(target => target -> res)
          )
        }
        CancelableFuture.sequence(futures).map(_.flatten.toMap)
    }
  }
  private def compile(
      connection: BuildServerConnection,
      targets: Seq[b.BuildTargetIdentifier],
  ): CancelableFuture[b.CompileResult] = {
    val params = new b.CompileParams(targets.asJava)
    targets.foreach(target => isCompiling(target) = true)
    val compilation = connection.compile(params)

    onStartCompilation()

    val result = compilation.asScala
      .andThen { case result =>
        updateCompiledTargetState(result)
        afterCompilation()

        // See https://github.com/scalacenter/bloop/issues/1067
        classes.rebuildIndex(
          targets,
          () => {
            refreshTestSuites()
            if (targets.exists(isCurrentlyFocused)) {
              languageClient.refreshModel()
            }
          },
        )
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
