package tests

import java.nio.file.Paths

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.Trees

object TreeUtils {
  def getTrees(scalaVersion: String): (Buffers, Trees) = getTrees(
    Some(scalaVersion)
  )

  def getScalaVersionSelector(
      scalaVersion: Option[String]
  ): ScalaVersionSelector = {
    val buildTargets = BuildTargets.empty
    new ScalaVersionSelector(
      () => UserConfiguration(fallbackScalaVersion = scalaVersion),
      buildTargets,
    )
  }

  def getTrees(scalaVersion: Option[String]): (Buffers, Trees) = {
    val buffers = Buffers()
    val selector = getScalaVersionSelector(scalaVersion)
    implicit val reports =
      new StdReportContext(Paths.get(".").toAbsolutePath, _ => None)
    val trees =
      new Trees(buffers, selector)
    (buffers, trees)
  }
}
