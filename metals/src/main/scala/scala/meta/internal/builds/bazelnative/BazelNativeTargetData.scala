package scala.meta.internal.builds.bazelnative

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._

/**
 * Thread-safe store for aspect-collected [[BspTargetInfo]] data.
 * Populated after each build; cleared on workspace reload.
 */
class BazelNativeTargetData {

  private val store =
    new ConcurrentHashMap[String, BspTargetInfo]()

  def update(data: Map[String, BspTargetInfo]): Unit = {
    store.clear()
    data.foreach { case (k, v) => store.put(k, v) }
  }

  def get(label: String): Option[BspTargetInfo] =
    Option(store.get(label))

  def allTargets: Map[String, BspTargetInfo] =
    store.asScala.toMap

  def labels: Set[String] =
    store.keySet().asScala.toSet

  def isEmpty: Boolean = store.isEmpty

  def clear(): Unit = store.clear()
}
