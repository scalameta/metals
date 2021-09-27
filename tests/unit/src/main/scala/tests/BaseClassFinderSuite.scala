package tests

import munit.FunSuite
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.ClassFinder
import scala.meta.internal.parsing.Trees

abstract class BaseClassFinderSuite extends FunSuite {
  def init(scalaVersion: String): (Buffers, ClassFinder) = {
    val buffers = Buffers()
    val buildTargets = new BuildTargets(_ => None)
    val selector = new ScalaVersionSelector(
      () => UserConfiguration(fallbackScalaVersion = Some(scalaVersion)),
      buildTargets
    )
    val trees = new Trees(buildTargets, buffers, selector)
    val classFinder = new ClassFinder(trees)
    (buffers, classFinder)
  }
}
