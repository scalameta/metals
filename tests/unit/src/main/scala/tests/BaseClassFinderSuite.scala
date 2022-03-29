package tests

import scala.meta.internal.metals.Buffers
import scala.meta.internal.parsing.ClassFinder

import munit.FunSuite

abstract class BaseClassFinderSuite extends FunSuite {
  def init(scalaVersion: String): (Buffers, ClassFinder) = {
    val (buffers, trees) = TreeUtils.getTrees(scalaVersion)
    val classFinder = new ClassFinder(trees)
    (buffers, classFinder)
  }
}
