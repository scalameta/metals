package tests

import java.nio.file.Paths

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

object TreeUtils {
  def getTrees(scalaVersion: String): (Buffers, Trees) = getTrees(
    Some(scalaVersion)
  )
  def getTrees(scalaVersion: Option[String]): (Buffers, Trees) = {
    val buffers = Buffers()
    val buildTargets = BuildTargets.empty
    val selector =
      new ScalaVersionSelector(
        () => UserConfiguration(fallbackScalaVersion = scalaVersion),
        buildTargets,
      )
    val trees = new Trees(buffers, selector, AbsolutePath(Paths.get(".")))
    (buffers, trees)
  }
}
