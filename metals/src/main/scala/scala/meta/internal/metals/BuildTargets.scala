package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.concurrent.TrieMap
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * In-memory cache for looking up build server metadata.
 */
final class BuildTargets() {
  private val sourceDirectoriesToBuildTarget =
    TrieMap.empty[AbsolutePath, ConcurrentLinkedQueue[BuildTargetIdentifier]]
  private val buildTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, BuildTarget]
  private val scalacTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, ScalacOptionsItem]

  def reset(): Unit = {
    sourceDirectoriesToBuildTarget.values.foreach(_.clear())
    sourceDirectoriesToBuildTarget.clear()
    buildTargetInfo.clear()
    scalacTargetInfo.clear()
  }

  def sourceDirectories: Iterable[AbsolutePath] =
    sourceDirectoriesToBuildTarget.keys

  def all: List[ScalaTarget] =
    for {
      (id, target) <- buildTargetInfo.toList
      scalac <- scalacTargetInfo.get(id)
    } yield ScalaTarget(target, scalac)

  def addSourceDirectory(
      directory: AbsolutePath,
      buildTarget: BuildTargetIdentifier
  ): Unit = {
    val queue = sourceDirectoriesToBuildTarget.getOrElseUpdate(
      directory,
      new ConcurrentLinkedQueue()
    )
    queue.add(buildTarget)
  }

  def addWorkspaceBuildTargets(result: WorkspaceBuildTargetsResult): Unit = {
    result.getTargets.asScala.foreach { target =>
      buildTargetInfo(target.getId) = target
    }
  }

  def addScalacOptions(result: ScalacOptionsResult): Unit = {
    result.getItems.asScala.foreach { item =>
      scalacTargetInfo(item.getTarget) = item
    }
  }

  def info(
      buildTarget: BuildTargetIdentifier
  ): Option[BuildTarget] =
    buildTargetInfo.get(buildTarget)

  def scalacOptions(
      buildTarget: BuildTargetIdentifier
  ): Option[ScalacOptionsItem] =
    scalacTargetInfo.get(buildTarget)

  /**
   * Returns the first build target containing this source file.
   */
  def inverseSources(
      textDocument: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    for {
      buildTargets <- sourceDirectoriesToBuildTarget.collectFirst {
        case (sourceDirectory, buildTargets)
            if textDocument.toNIO.startsWith(sourceDirectory.toNIO) =>
          buildTargets.asScala
      }
      target <- buildTargets // prioritize JVM targets over JS/Native
        .find(x => scalacOptions(x).exists(_.isJVM))
        .orElse(buildTargets.headOption)
    } yield target
  }

}
