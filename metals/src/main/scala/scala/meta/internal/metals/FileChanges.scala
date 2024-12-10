package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

class FileChanges(buildTargets: BuildTargets, workspace: () => AbsolutePath)(
    implicit ec: ExecutionContext
) {
  private val previousSignatures = TrieMap[AbsolutePath, String]()
  private val dirtyBuildTargets = mutable.Set[BuildTargetIdentifier]()

  def addAllDirty(
      targets: IterableOnce[BuildTargetIdentifier]
  ): mutable.Set[BuildTargetIdentifier] =
    dirtyBuildTargets.addAll(targets)

  def buildTargetsToCompile(
      paths: Seq[(AbsolutePath, Fingerprint)],
      focusedDocumentBuildTarget: Option[BuildTargetIdentifier],
  ): Future[Seq[BuildTargetIdentifier]] =
    for {
      toCompile <- paths.foldLeft(
        Future.successful(Seq.empty[BuildTargetIdentifier])
      ) { case (toCompile, (path, fingerprint)) =>
        toCompile.flatMap { acc =>
          findBuildTargetIfShouldCompile(path, Some(fingerprint)).map(acc ++ _)
        }
      }
    } yield {
      val allToCompile =
        if (focusedDocumentBuildTarget.exists(dirtyBuildTargets(_)))
          toCompile ++ focusedDocumentBuildTarget
        else toCompile
      willCompile(allToCompile)
      allToCompile
    }

  def buildTargetToCompile(
      path: AbsolutePath,
      fingerprint: Option[Fingerprint],
      assumeDidNotChange: Boolean,
  ): Future[Option[BuildTargetIdentifier]] = {
    for {
      toCompile <- findBuildTargetIfShouldCompile(
        path,
        fingerprint,
        assumeDidNotChange,
      )
    } yield {
      willCompile(toCompile.toSeq)
      toCompile
    }
  }

  def willCompile(ids: Seq[BuildTargetIdentifier]): Unit =
    buildTargets
      .buildTargetTransitiveDependencies(ids.toList)
      .foreach(dirtyBuildTargets.remove)

  private def findBuildTargetIfShouldCompile(
      path: AbsolutePath,
      fingerprint: Option[Fingerprint],
      assumeDidNotChange: Boolean = false,
  ): Future[Option[BuildTargetIdentifier]] = {
    expand(path).map(
      _.filter(bt =>
        dirtyBuildTargets.contains(
          bt
        ) || (!assumeDidNotChange && didContentChange(path, fingerprint, bt))
      )
    )
  }

  private def expand(
      path: AbsolutePath
  ): Future[Option[BuildTargetIdentifier]] = {
    val isCompilable =
      (path.isScalaOrJava || path.isSbt) &&
        !path.isDependencySource(workspace()) &&
        !path.isInTmpDirectory(workspace())

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

  private def didContentChange(
      path: AbsolutePath,
      fingerprint: Option[Fingerprint],
      buildTarget: BuildTargetIdentifier,
  ): Boolean = {
    val didChange = didContentChange(path, fingerprint)
    if (didChange) {
      buildTargets
        .allInverseDependencies(buildTarget)
        .foreach { bt =>
          if (bt != buildTarget) dirtyBuildTargets.add(bt)
        }
    }
    didChange
  }

  private def didContentChange(
      path: AbsolutePath,
      fingerprint: Option[Fingerprint],
  ): Boolean = {
    fingerprint
      .map { fingerprint =>
        synchronized {
          if (previousSignatures.getOrElse(path, null) == fingerprint.md5)
            false
          else {
            previousSignatures.put(path, fingerprint.md5)
            true
          }
        }
      }
      .getOrElse(true)
  }

  def cancel(): Unit = previousSignatures.clear()
}
