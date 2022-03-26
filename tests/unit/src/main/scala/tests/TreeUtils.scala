package tests

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees

object TreeUtils {
  def getTrees(scalaVersion: String): (Buffers, Trees) = getTrees(Some(scalaVersion))
  def getTrees(scalaVersion: Option[String]): (Buffers, Trees) = {
    val buffers = Buffers()
    val buildTargets = new BuildTargets()
    val selector =
      new ScalaVersionSelector(
        () => UserConfiguration(fallbackScalaVersion = scalaVersion),
        buildTargets
      )
    val trees = new Trees(buffers, selector)
    (buffers, trees)
  }
}
