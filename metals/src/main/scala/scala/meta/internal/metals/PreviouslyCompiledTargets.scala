package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

/**
 * When there are some upstream compile errors we remove diagnostics from downstream targets,
 * since those can be stale. Not to give a false impression, that the project compiles
 * when upstream errors get fixed, we map compilation of upstream targets to
 * appropriate downstream targets. This class holds this mapping.
 */
class PreviouslyCompiledTargets {
  private val map =
    TrieMap.empty[BuildTargetIdentifier, Set[BuildTargetIdentifier]]

  def map(targets: Seq[BuildTargetIdentifier]): Seq[BuildTargetIdentifier] = {
    if (map.isEmpty) targets
    else {
      val finalSet = mutable.Set[BuildTargetIdentifier]()
      for (key <- targets)
        map.get(key) match {
          case Some(set) if set.nonEmpty => finalSet ++= set
          case _ => finalSet += key
        }
      finalSet.toSeq
    }
  }

  def addMapping(
      id: BuildTargetIdentifier,
      to: Set[BuildTargetIdentifier],
  ): Option[Set[BuildTargetIdentifier]] = synchronized {
    val newValue = map.get(id).map(to ++ _).getOrElse(to)
    map.put(id, newValue)
  }

  def remove(id: BuildTargetIdentifier): Unit = synchronized {
    for (key <- map.keySet) {
      for {
        oldSet <- map.get(key)
        if (oldSet.contains(id))
      } map.put(id, oldSet - id)
    }
  }
}
